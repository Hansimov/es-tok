#!/usr/bin/env python3

import argparse
import base64
import json
import ssl
import time
import urllib.parse
import urllib.request
from pathlib import Path


SOURCE_FIELDS = [
    "bvid",
    "title",
    "tags",
    "desc",
    "owner.mid",
    "owner.name",
    "stat.view",
    "insert_at",
]

TYPO_SUBSTITUTIONS = {
    "里": "理",
    "理": "里",
    "影": "映",
    "映": "影",
    "猫": "毛",
    "毛": "猫",
    "总": "中",
    "中": "总",
    "动": "冻",
    "冻": "动",
    "学": "雪",
    "雪": "学",
    "生": "声",
    "声": "生",
}


def collapse_text(value) -> str:
    if value is None:
        return ""
    return " ".join(str(value).split()).strip()


def normalize_text(value) -> str:
    return collapse_text(value).lower()


def owner_info(doc: dict) -> dict:
    return doc.get("owner") or {}


def parse_tag_text(doc: dict) -> list[str]:
    tags = doc.get("tags", "")
    if isinstance(tags, list):
        items = tags
    else:
        items = str(tags).replace("#", " ").split(",")
    return [collapse_text(item) for item in items if collapse_text(item)]


def useful_text(doc: dict) -> bool:
    title = collapse_text(doc.get("title", ""))
    desc = collapse_text(doc.get("desc", ""))
    return len(title) >= 6 or len(desc) >= 16


def mutate_single_typo(text: str) -> str:
    if not text:
        return text
    for original, replacement in TYPO_SUBSTITUTIONS.items():
        if original in text:
            return text.replace(original, replacement, 1)
    return text


def build_text_variants(doc: dict) -> list[dict]:
    title = collapse_text(doc.get("title", ""))
    desc = collapse_text(doc.get("desc", ""))
    tags = parse_tag_text(doc)
    variants = []

    combo_parts = [title]
    combo_parts.extend(tags[:2])
    combo_text = collapse_text(" ".join(part for part in combo_parts if part))
    if len(combo_text) >= 8:
        variants.append({"kind": "combo", "text": combo_text})

    long_parts = [title]
    if desc:
        long_parts.append(desc[:32])
    elif tags:
        long_parts.extend(tags[:3])
    long_text = collapse_text(" ".join(part for part in long_parts if part))
    if len(long_text) >= 12:
        variants.append({"kind": "long", "text": long_text})

    typo_text = mutate_single_typo(long_text or combo_text)
    if typo_text and typo_text != (long_text or combo_text):
        variants.append({"kind": "typo", "text": typo_text})

    deduped = []
    seen = set()
    for variant in variants:
        key = normalize_text(variant["text"])
        if not key or key in seen:
            continue
        seen.add(key)
        deduped.append(variant)
    return deduped


def build_auth_header(user: str, password: str) -> str | None:
    if not user:
        return None
    token = base64.b64encode(f"{user}:{password}".encode("utf-8")).decode("ascii")
    return f"Basic {token}"


def request_json(
    base_url: str, path: str, auth_header: str | None, ssl_context, payload=None
):
    url = urllib.parse.urljoin(base_url, path)
    data = None
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url, data=data, method="POST" if data is not None else "GET"
    )
    request.add_header("Content-Type", "application/json")
    if auth_header:
        request.add_header("Authorization", auth_header)
    started = time.perf_counter()
    with urllib.request.urlopen(request, context=ssl_context, timeout=60) as response:
        body = json.loads(response.read().decode("utf-8"))
    elapsed_ms = round((time.perf_counter() - started) * 1000.0, 2)
    return body, elapsed_ms


def fetch_docs(
    base_url: str,
    auth_header: str | None,
    ssl_context,
    index: str,
    size: int,
    sort_clause: list[dict],
):
    payload = {
        "size": size,
        "sort": sort_clause,
        "_source": SOURCE_FIELDS,
        "query": {"bool": {"filter": [{"exists": {"field": "title.words"}}]}},
    }
    body, _ = request_json(
        base_url, f"/{index}/_search", auth_header, ssl_context, payload
    )
    return [hit.get("_source", {}) for hit in body.get("hits", {}).get("hits", [])]


def collect_docs(
    base_url: str, auth_header: str | None, ssl_context, index: str, fetch_size: int
):
    combined = []
    seen = set()
    for docs in (
        fetch_docs(
            base_url,
            auth_header,
            ssl_context,
            index,
            fetch_size,
            [{"insert_at": "desc"}],
        ),
        fetch_docs(
            base_url,
            auth_header,
            ssl_context,
            index,
            fetch_size,
            [{"stat.view": "desc"}],
        ),
    ):
        for doc in docs:
            bvid = doc.get("bvid")
            if not bvid or bvid in seen or not useful_text(doc):
                continue
            seen.add(bvid)
            combined.append(doc)
    return combined


def build_cases(docs: list[dict], sample_size: int):
    cases = []
    for doc in docs:
        variants = build_text_variants(doc)
        if not variants:
            continue
        for variant in variants:
            cases.append(
                {
                    "label": f"{doc.get('bvid')}:{variant['kind']}",
                    "variant": variant["kind"],
                    "seed": {
                        "bvid": doc.get("bvid"),
                        "title": doc.get("title", ""),
                        "owner": owner_info(doc),
                        "tags": parse_tag_text(doc),
                    },
                    "text": variant["text"],
                }
            )
            if len(cases) >= sample_size:
                return cases
    return cases


def seed_topic_tokens(seed: dict) -> set[str]:
    tokens = set()
    for value in [seed.get("title", ""), *seed.get("tags", [])]:
        normalized = normalize_text(value)
        for token in normalized.replace(",", " ").split():
            if len(token) >= 2:
                tokens.add(token)
    return tokens


def summarize_token_response(case: dict, response: dict, latency_ms: float):
    options = response.get("options", [])
    seed_tokens = seed_topic_tokens(case["seed"])
    returned_texts = [normalize_text(option.get("text", "")) for option in options]
    overlap = [
        text
        for text in returned_texts
        if any(token and token in text for token in seed_tokens)
    ]
    notes = []
    anomalies = []
    if not options:
        anomalies.append("no_results")
    elif not overlap:
        notes.append("weak_topic_overlap")
    if case["variant"] in {"long", "typo"} and len(options) < 2:
        notes.append("few_results")
    return {
        "result_count": len(options),
        "latency_ms": latency_ms,
        "anomalies": anomalies,
        "notes": notes,
        "top_texts": returned_texts[:5],
    }


def summarize_owner_response(case: dict, response: dict, latency_ms: float):
    owners = response.get("owners", [])
    seed_mid = (case["seed"].get("owner") or {}).get("mid")
    mids = [owner.get("mid") for owner in owners]
    notes = []
    anomalies = []
    if not owners:
        anomalies.append("no_results")
    elif seed_mid and seed_mid not in mids[:5]:
        notes.append("seed_owner_missing")
    if case["variant"] in {"long", "typo"} and len(owners) < 2:
        notes.append("few_results")
    return {
        "result_count": len(owners),
        "latency_ms": latency_ms,
        "anomalies": anomalies,
        "notes": notes,
        "top_mids": mids[:5],
    }


def evaluate_cases(
    base_url: str, auth_header: str | None, ssl_context, index: str, cases: list[dict]
):
    results = []
    for case in cases:
        token_body = {
            "text": case["text"],
            "mode": "auto",
            "fields": ["title.words", "tags.words"],
            "size": 8,
            "scan_limit": 128,
            "correction_min_length": 2,
            "correction_max_edits": 2,
            "correction_prefix_length": 1,
        }
        owner_body = {
            "text": case["text"],
            "fields": ["title.words", "tags.words", "desc.words"],
            "size": 5,
            "scan_limit": 128,
            "use_pinyin": True,
        }
        token_response, token_latency = request_json(
            base_url,
            f"/{index}/_es_tok/related_tokens_by_tokens",
            auth_header,
            ssl_context,
            token_body,
        )
        owner_response, owner_latency = request_json(
            base_url,
            f"/{index}/_es_tok/related_owners_by_tokens",
            auth_header,
            ssl_context,
            owner_body,
        )
        results.append(
            {
                "label": case["label"],
                "variant": case["variant"],
                "text": case["text"],
                "seed": case["seed"],
                "responses": [
                    {
                        "relation": "related_tokens_by_tokens",
                        "request": token_body,
                        "summary": summarize_token_response(
                            case, token_response, token_latency
                        ),
                        "response": token_response,
                    },
                    {
                        "relation": "related_owners_by_tokens",
                        "request": owner_body,
                        "summary": summarize_owner_response(
                            case, owner_response, owner_latency
                        ),
                        "response": owner_response,
                    },
                ],
            }
        )
    return results


def build_summary(cases: list[dict]):
    summary = {
        "by_relation": {},
        "anomaly_counts": {},
        "note_counts": {},
        "flagged_cases": [],
        "noted_cases": [],
    }
    for case in cases:
        for response in case["responses"]:
            relation = response["relation"]
            bucket = summary["by_relation"].setdefault(
                relation,
                {
                    "cases": 0,
                    "empty": 0,
                    "avg_result_count": 0.0,
                    "avg_latency_ms": 0.0,
                    "flagged": 0,
                },
            )
            result_summary = response["summary"]
            bucket["cases"] += 1
            bucket["avg_result_count"] += result_summary["result_count"]
            bucket["avg_latency_ms"] += result_summary["latency_ms"]
            if result_summary["result_count"] == 0:
                bucket["empty"] += 1
            if result_summary["anomalies"]:
                bucket["flagged"] += 1
                summary["flagged_cases"].append(
                    {
                        "label": case["label"],
                        "relation": relation,
                        "anomalies": result_summary["anomalies"],
                    }
                )
            for anomaly in result_summary["anomalies"]:
                summary["anomaly_counts"][anomaly] = (
                    summary["anomaly_counts"].get(anomaly, 0) + 1
                )
            if result_summary["notes"]:
                summary["noted_cases"].append(
                    {
                        "label": case["label"],
                        "relation": relation,
                        "notes": result_summary["notes"],
                    }
                )
            for note in result_summary["notes"]:
                summary["note_counts"][note] = summary["note_counts"].get(note, 0) + 1
    for bucket in summary["by_relation"].values():
        if bucket["cases"]:
            bucket["avg_result_count"] = round(
                bucket["avg_result_count"] / bucket["cases"], 2
            )
            bucket["avg_latency_ms"] = round(
                bucket["avg_latency_ms"] / bucket["cases"], 2
            )
    return summary


def build_markdown(report: dict) -> str:
    lines = [
        f"# Text Related Report Summary: {report['index']}",
        "",
        f"- case_count: {report['case_count']}",
        f"- fetch_size: {report['fetch_size']}",
        f"- sample_size: {report['sample_size']}",
        "",
        "## By Relation",
    ]
    for relation, stats in report["summary"]["by_relation"].items():
        lines.append(
            f"- {relation}: cases={stats['cases']} empty={stats['empty']} flagged={stats['flagged']} avg_result_count={stats['avg_result_count']} avg_latency_ms={stats['avg_latency_ms']}"
        )
    lines.extend(["", "## Failures"])
    flagged = report["summary"].get("flagged_cases", [])
    if not flagged:
        lines.append("- none")
    else:
        for case in flagged[:30]:
            lines.append(
                f"- {case['label']} | {case['relation']} | anomalies={', '.join(case['anomalies'])}"
            )
    lines.extend(["", "## Review Notes"])
    noted = report["summary"].get("noted_cases", [])
    if not noted:
        lines.append("- none")
    else:
        for case in noted[:40]:
            lines.append(
                f"- {case['label']} | {case['relation']} | notes={', '.join(case['notes'])}"
            )
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output", default="build/reports/text_related_real_case_report.json"
    )
    parser.add_argument("--bad-case-output", default="")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", default="19203")
    parser.add_argument("--scheme", default="https")
    parser.add_argument("--user", default="elastic")
    parser.add_argument("--password", default="")
    parser.add_argument("--index", default="bili_videos_dev6")
    parser.add_argument("--fetch-size", type=int, default=48)
    parser.add_argument("--sample-size", type=int, default=18)
    parser.add_argument(
        "--ca-cert", default="/media/ssd/elasticsearch-docker-9.2.4-dev/certs/ca/ca.crt"
    )
    args = parser.parse_args()

    base_url = f"{args.scheme}://{args.host}:{args.port}"
    auth_header = build_auth_header(args.user, args.password)
    ssl_context = (
        ssl.create_default_context(cafile=args.ca_cert)
        if args.ca_cert
        else ssl.create_default_context()
    )

    docs = collect_docs(base_url, auth_header, ssl_context, args.index, args.fetch_size)
    cases = build_cases(docs, args.sample_size)
    results = evaluate_cases(base_url, auth_header, ssl_context, args.index, cases)
    report = {
        "index": args.index,
        "fetch_size": args.fetch_size,
        "sample_size": args.sample_size,
        "case_count": len(results),
        "summary": build_summary(results),
        "cases": results,
    }

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    bad_case_output = args.bad_case_output or f"{output_path}.summary.md"
    Path(bad_case_output).write_text(build_markdown(report), encoding="utf-8")
    print(
        json.dumps(
            {
                "output": str(output_path),
                "bad_case_output": bad_case_output,
                "summary": report["summary"],
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
