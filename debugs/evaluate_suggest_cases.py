#!/usr/bin/env python3

import argparse
import json
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


DEFAULT_FIELDS = {
    "prefix": ["title.words", "tags.words"],
    "correction": ["title.words", "tags.words"],
    "associate": ["title.words", "tags.words"],
    "next_token": ["title.words", "tags.words"],
    "auto": ["title.words", "tags.words"],
}

FIELD_ALIASES = {
    "owner.name.words": "owner.name.suggest",
    "pages.parts.words": "pages.parts.suggest",
}

NEXT_TOKEN_PHRASE_CONTINUATION_IDS = {
    "campus_hide_seek",
    "neighbor_likes_dad",
    "teacher_raise_head",
    "qingyu_account_case",
    "xiongda_no1",
    "school_apocalypse",
    "curfew_after_dlc",
    "gold_phone_case",
    "bear_core_analysis",
    "gansu_boyfriend",
    "aerospace_base",
    "imitate_cat_actions",
    "anhe_bridge",
    "northeast_grace",
    "airport_closed",
    "defend_radish",
    "simba_daniel",
    "biscuit_union",
    "little_penguin",
    "firecracker_bag",
    "geely_ad",
    "xiongda_kuaipao",
    "silent_hill_mother",
}

DEFAULT_REQUEST = {
    "prefix": {"size": 5, "scan_limit": 128},
    "correction": {
        "size": 5,
        "correction_min_length": 2,
        "correction_max_edits": 2,
        "correction_prefix_length": 1,
    },
    "associate": {"size": 10, "scan_limit": 128},
    "next_token": {"size": 10, "scan_limit": 128},
    "auto": {
        "size": 8,
        "scan_limit": 128,
        "correction_min_length": 2,
        "correction_max_edits": 2,
        "correction_prefix_length": 1,
    },
}


def normalize_text(text: str) -> str:
    if text is None:
        return ""
    collapsed = " ".join(text.split())
    if not collapsed:
        return ""

    ascii_letters_or_digits = sum(
        1 for ch in collapsed if ord(ch) < 128 and ch.isalnum()
    )
    non_ascii = sum(1 for ch in collapsed if ord(ch) >= 128 and not ch.isspace())
    if " " in collapsed and non_ascii > 0 and non_ascii >= ascii_letters_or_digits:
        return collapsed.replace(" ", "")
    return collapsed


def flatten_cases(sources):
    cases = []
    for source in sources:
        for mode in ("prefix", "correction", "associate", "next_token", "auto"):
            payload = source.get(mode)
            if not payload:
                continue
            normalized_mode = normalize_mode(mode)
            label = case_label(source["id"], mode, payload)
            expected = payload.get("expected", {})
            top_k = expected.get("top_k", DEFAULT_REQUEST[normalized_mode]["size"])
            cases.append(
                {
                    "id": payload.get("id", f"{source['id']}_{normalized_mode}"),
                    "mode": normalized_mode,
                    "label": label,
                    "text": payload["text"],
                    "fields": normalize_fields(
                        payload.get(
                            "fields", default_fields(normalized_mode, label, payload)
                        )
                    ),
                    "request": payload.get("request", {}),
                    "expected": {
                        "any_of": [
                            normalize_text(item) for item in expected.get("any_of", [])
                        ],
                        "top_k": top_k,
                    },
                    "regression": bool(payload.get("regression", False)),
                    "source": source["source"],
                    "notes": payload.get("notes", ""),
                }
            )
    return cases


def case_label(source_id, mode, payload):
    if mode not in ("associate", "next_token", "auto"):
        return mode
    if payload.get("kind"):
        return payload["kind"]
    if source_id in NEXT_TOKEN_PHRASE_CONTINUATION_IDS:
        return "phrase_continuation"
    return "associative_completion" if mode in ("associate", "next_token") else "auto"


def default_fields(mode, label, payload):
    request = payload.get("request", {})
    if request.get("use_pinyin"):
        if (
            mode in ("associate", "next_token", "auto")
            and label == "phrase_continuation"
        ):
            return ["title.suggest"]
        return ["tags.suggest", "title.suggest"]
    if mode in ("associate", "next_token", "auto") and label == "phrase_continuation":
        return ["title.words"]
    return DEFAULT_FIELDS[mode]


def normalize_fields(fields):
    return [FIELD_ALIASES.get(field, field) for field in fields]


def build_request(case):
    request = {
        "text": case["text"],
        "mode": normalize_mode(case["mode"]),
        "fields": case["fields"],
    }
    request.update(DEFAULT_REQUEST[case["mode"]])
    request.update(case["request"])
    return request


def normalize_mode(mode):
    return "associate" if mode == "next_token" else mode


def evaluate_case(base_url, auth_header, ssl_context, index_name, case):
    url = urllib.parse.urljoin(base_url, f"/{index_name}/_es_tok/suggest")
    payload = json.dumps(build_request(case)).encode("utf-8")
    request = urllib.request.Request(url, data=payload, method="POST")
    request.add_header("Content-Type", "application/json")
    if auth_header:
        request.add_header("Authorization", auth_header)

    with urllib.request.urlopen(request, context=ssl_context, timeout=30) as response:
        body = json.loads(response.read().decode("utf-8"))

    options = body.get("options", [])
    normalized = [normalize_text(option.get("text", "")) for option in options]
    top_k = case["expected"]["top_k"]
    expected_any = set(case["expected"]["any_of"])
    matched = None
    if expected_any:
        for option_text in normalized[:top_k]:
            if option_text in expected_any:
                matched = option_text
                break

    return {
        "id": case["id"],
        "mode": case["mode"],
        "label": case["label"],
        "text": case["text"],
        "source": case["source"],
        "regression": case["regression"],
        "notes": case["notes"],
        "expected": case["expected"],
        "request": build_request(case),
        "matched": matched,
        "passed": matched is not None if expected_any else len(options) > 0,
        "options": options,
        "normalized_options": normalized,
    }


def summarize(results):
    summary = {"overall": {}, "by_mode": {}, "by_label": {}, "regression": {}}

    def stats(items):
        total = len(items)
        passed = sum(1 for item in items if item["passed"])
        return {
            "total": total,
            "passed": passed,
            "failed": total - passed,
            "pass_rate": round((passed / total) * 100.0, 2) if total else 0.0,
        }

    summary["overall"] = stats(results)
    for mode in ("prefix", "correction", "associate", "auto"):
        mode_results = [item for item in results if item["mode"] == mode]
        summary["by_mode"][mode] = stats(mode_results)

    for label in (
        "prefix",
        "correction",
        "phrase_continuation",
        "associative_completion",
        "auto",
    ):
        label_results = [item for item in results if item["label"] == label]
        if label_results:
            summary["by_label"][label] = stats(label_results)

    regression_results = [item for item in results if item["regression"]]
    summary["regression"] = stats(regression_results)
    return summary


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", default="testing/golden/suggest_real_cases.json")
    parser.add_argument(
        "--output", default="build/reports/suggest_real_case_report.json"
    )
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", default="19203")
    parser.add_argument("--scheme", default="https")
    parser.add_argument("--user", default="elastic")
    parser.add_argument("--password", default="")
    parser.add_argument("--index", default="bili_videos_dev6")
    parser.add_argument(
        "--ca-cert", default="/media/ssd/elasticsearch-docker-9.2.4-dev/certs/ca/ca.crt"
    )
    args = parser.parse_args()

    case_path = Path(args.cases)
    sources = json.loads(case_path.read_text(encoding="utf-8"))
    cases = flatten_cases(sources)

    base_url = f"{args.scheme}://{args.host}:{args.port}"
    auth_header = None
    if args.user or args.password:
        token = (f"{args.user}:{args.password}").encode("utf-8")
        import base64

        auth_header = "Basic " + base64.b64encode(token).decode("ascii")

    ssl_context = ssl.create_default_context(cafile=args.ca_cert)
    results = []
    for case in cases:
        try:
            results.append(
                evaluate_case(base_url, auth_header, ssl_context, args.index, case)
            )
        except urllib.error.HTTPError as exc:
            results.append(
                {
                    "id": case["id"],
                    "mode": case["mode"],
                    "label": case["label"],
                    "text": case["text"],
                    "source": case["source"],
                    "regression": case["regression"],
                    "notes": case["notes"],
                    "expected": case["expected"],
                    "request": build_request(case),
                    "passed": False,
                    "matched": None,
                    "options": [],
                    "normalized_options": [],
                    "error": f"HTTP {exc.code}",
                }
            )

    report = {
        "summary": summarize(results),
        "results": results,
    }

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    print(json.dumps(report["summary"], ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
