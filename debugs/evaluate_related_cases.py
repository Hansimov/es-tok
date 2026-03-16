#!/usr/bin/env python3

import argparse
import base64
import json
import ssl
import urllib.parse
import urllib.request
from pathlib import Path


DEFAULT_SOURCE_FIELDS = [
    "bvid",
    "title",
    "owner.mid",
    "owner.name",
    "tags",
    "desc",
    "stat_score",
    "stat.view",
    "insert_at",
]


def normalize_seed_text(value) -> str:
    if value is None:
        return ""
    return " ".join(str(value).split()).strip().lower()


def looks_like_useful_token(token: str) -> bool:
    token = normalize_seed_text(token)
    if len(token) < 2:
        return False
    if token.isdigit():
        return False
    return any((ord(ch) > 127 and not ch.isspace()) or ch.isalpha() for ch in token)


def seed_has_useful_signal(seed: dict, include_owner_name: bool = True) -> bool:
    owner = seed.get("owner") or {}
    candidates = [seed.get("title", ""), seed.get("tags", ""), seed.get("desc", "")]
    if include_owner_name:
        candidates.append(owner.get("name", ""))
    for value in candidates:
        normalized = normalize_seed_text(value)
        if not normalized:
            continue
        for token in normalized.replace(",", " ").split():
            if looks_like_useful_token(token):
                return True
    return False


def top_items(response: dict):
    if "videos" in response:
        return response.get("videos", [])
    if "owners" in response:
        return response.get("owners", [])
    return []


def summarize_relation(seed: dict, relation: str, request: dict, response: dict):
    items = top_items(response)
    summary = {
        "result_count": len(items),
        "top_score": items[0].get("score", 0.0) if items else 0.0,
        "top_doc_freq": items[0].get("doc_freq", 0) if items else 0,
        "anomalies": [],
        "notes": [],
    }

    if not items:
        summary["anomalies"].append("no_results")

    if relation == "related_videos_by_videos":
        seed_bvids = set(request.get("bvids", []))
        returned_bvids = {item.get("bvid") for item in items}
        if seed_bvids & returned_bvids:
            summary["anomalies"].append("seed_video_leaked")
        if items and len({item.get("owner_mid") for item in items}) == 1:
            summary["notes"].append("single_owner_cluster")
        if 0 < len(items) < min(2, request.get("size", 5)):
            summary["notes"].append("few_results")

    if relation == "related_owners_by_videos":
        seed_mid = ((seed.get("owner") or {}).get("mid")) or seed.get("mid")
        returned_mids = [item.get("mid") for item in items]
        if returned_mids and seed_mid and all(mid == seed_mid for mid in returned_mids):
            summary["notes"].append("same_owner_only")
        if 0 < len(items) < min(2, request.get("size", 5)):
            summary["notes"].append("few_results")

    if relation == "related_videos_by_owners":
        if items and len({item.get("owner_mid") for item in items}) == 1:
            summary["notes"].append("single_owner_cluster")
        if 0 < len(items) < min(2, request.get("size", 5)):
            summary["notes"].append("few_results")

    if relation == "related_owners_by_owners":
        seed_mids = set(request.get("mids", []))
        returned_mids = [item.get("mid") for item in items]
        if seed_mids & set(returned_mids):
            summary["anomalies"].append("seed_owner_leaked")
        if items and all((item.get("doc_freq", 0) <= 1) for item in items[:3]):
            summary["notes"].append("low_support_top_results")
        if 0 < len(items) < min(2, request.get("size", 5)):
            summary["notes"].append("few_results")

    return summary


def build_report_summary(cases):
    summary = {
        "by_relation": {},
        "anomaly_counts": {},
        "note_counts": {},
        "flagged_cases": [],
        "noted_cases": [],
    }
    for case in cases:
        for response_entry in case["responses"]:
            relation = response_entry["relation"]
            relation_summary = response_entry["summary"]
            bucket = summary["by_relation"].setdefault(
                relation,
                {"cases": 0, "empty": 0, "flagged": 0, "avg_result_count": 0.0},
            )
            bucket["cases"] += 1
            bucket["avg_result_count"] += relation_summary["result_count"]
            if relation_summary["result_count"] == 0:
                bucket["empty"] += 1
            if relation_summary["anomalies"]:
                bucket["flagged"] += 1
                summary["flagged_cases"].append(
                    {
                        "label": case["label"],
                        "relation": relation,
                        "anomalies": relation_summary["anomalies"],
                    }
                )
            for anomaly in relation_summary["anomalies"]:
                summary["anomaly_counts"][anomaly] = (
                    summary["anomaly_counts"].get(anomaly, 0) + 1
                )
            if relation_summary["notes"]:
                summary["noted_cases"].append(
                    {
                        "label": case["label"],
                        "relation": relation,
                        "notes": relation_summary["notes"],
                    }
                )
            for note in relation_summary["notes"]:
                summary["note_counts"][note] = summary["note_counts"].get(note, 0) + 1

    for bucket in summary["by_relation"].values():
        if bucket["cases"]:
            bucket["avg_result_count"] = round(
                bucket["avg_result_count"] / bucket["cases"], 2
            )
    return summary


def build_bad_case_lines(report: dict):
    lines = [
        f"# Related Report Summary: {report['index']}",
        "",
        f"- case_count: {report['case_count']}",
        f"- recent_fetch_size: {report['recent_fetch_size']}",
        f"- video_sample_size: {report['video_sample_size']}",
        f"- owner_sample_size: {report['owner_sample_size']}",
        "",
        "## By Relation",
    ]
    for relation, stats in report["summary"]["by_relation"].items():
        lines.append(
            f"- {relation}: cases={stats['cases']} empty={stats['empty']} flagged={stats['flagged']} avg_result_count={stats['avg_result_count']}"
        )

    lines.extend(["", "## Failures"])
    flagged_cases = report["summary"].get("flagged_cases", [])
    if not flagged_cases:
        lines.append("- none")
    else:
        for case in flagged_cases[:30]:
            lines.append(
                f"- {case['label']} | {case['relation']} | anomalies={', '.join(case['anomalies'])}"
            )

    lines.extend(["", "## Review Notes"])
    noted_cases = report["summary"].get("noted_cases", [])
    if not noted_cases:
        lines.append("- none")
    else:
        for case in noted_cases[:30]:
            lines.append(
                f"- {case['label']} | {case['relation']} | notes={', '.join(case['notes'])}"
            )

    lines.extend(["", "## Top Notes By Relation"])
    notes_by_relation = {}
    for case in noted_cases:
        bucket = notes_by_relation.setdefault(case["relation"], [])
        bucket.append(case)
    if not notes_by_relation:
        lines.append("- none")
    else:
        for relation in sorted(notes_by_relation):
            lines.append(f"### {relation}")
            for case in notes_by_relation[relation][:10]:
                lines.append(f"- {case['label']} | notes={', '.join(case['notes'])}")

    return lines


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
    with urllib.request.urlopen(request, context=ssl_context, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def fetch_recent_docs(
    base_url: str, auth_header: str | None, ssl_context, index_name: str, size: int
):
    payload = {
        "size": size,
        "sort": [{"insert_at": "desc"}],
        "_source": DEFAULT_SOURCE_FIELDS,
        "query": {"match_all": {}},
    }
    response = request_json(
        base_url, f"/{index_name}/_search", auth_header, ssl_context, payload
    )
    return [hit.get("_source", {}) for hit in response.get("hits", {}).get("hits", [])]


def build_cases(recent_docs, video_sample_size: int, owner_sample_size: int):
    cases = []
    skipped_video_seeds = 0
    for doc in recent_docs[:video_sample_size]:
        bvid = doc.get("bvid")
        if not bvid:
            continue
        if not seed_has_useful_signal(doc, include_owner_name=False):
            skipped_video_seeds += 1
            continue
        cases.append(
            {
                "label": f"video:{bvid}",
                "seed": doc,
                "requests": [
                    {
                        "relation": "related_videos_by_videos",
                        "body": {"bvids": [bvid], "size": 5, "scan_limit": 64},
                    },
                    {
                        "relation": "related_owners_by_videos",
                        "body": {"bvids": [bvid], "size": 5, "scan_limit": 64},
                    },
                ],
            }
        )

    owner_cases = []
    skipped_owner_seeds = 0
    seen_mids = set()
    for doc in recent_docs:
        owner = doc.get("owner") or {}
        mid = owner.get("mid")
        if not mid or mid in seen_mids:
            continue
        seen_mids.add(mid)
        if not seed_has_useful_signal(doc, include_owner_name=True):
            skipped_owner_seeds += 1
            continue
        owner_cases.append(
            {
                "label": f"owner:{mid}",
                "seed": {
                    "mid": mid,
                    "name": owner.get("name", ""),
                    "sample_bvid": doc.get("bvid", ""),
                    "sample_title": doc.get("title", ""),
                },
                "requests": [
                    {
                        "relation": "related_videos_by_owners",
                        "body": {"mids": [mid], "size": 5, "scan_limit": 64},
                    },
                    {
                        "relation": "related_owners_by_owners",
                        "body": {"mids": [mid], "size": 5, "scan_limit": 64},
                    },
                ],
            }
        )
        if len(owner_cases) >= owner_sample_size:
            break

    return cases + owner_cases, {
        "skipped_video_seeds": skipped_video_seeds,
        "skipped_owner_seeds": skipped_owner_seeds,
    }


def evaluate_cases(
    base_url: str, auth_header: str | None, ssl_context, index_name: str, cases
):
    results = []
    for case in cases:
        case_result = {
            "label": case["label"],
            "seed": case["seed"],
            "responses": [],
        }
        for query in case["requests"]:
            response = request_json(
                base_url,
                f"/{index_name}/_es_tok/{query['relation']}",
                auth_header,
                ssl_context,
                query["body"],
            )
            summary = summarize_relation(
                case["seed"], query["relation"], query["body"], response
            )
            case_result["responses"].append(
                {
                    "relation": query["relation"],
                    "request": query["body"],
                    "summary": summary,
                    "response": response,
                }
            )
        results.append(case_result)
    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output", default="build/reports/related_real_case_report.json"
    )
    parser.add_argument("--bad-case-output", default="")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", default="19203")
    parser.add_argument("--scheme", default="https")
    parser.add_argument("--user", default="elastic")
    parser.add_argument("--password", default="")
    parser.add_argument("--index", default="bili_videos_dev6")
    parser.add_argument("--video-sample-size", type=int, default=6)
    parser.add_argument("--owner-sample-size", type=int, default=6)
    parser.add_argument("--recent-fetch-size", type=int, default=24)
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
    recent_docs = fetch_recent_docs(
        base_url, auth_header, ssl_context, args.index, args.recent_fetch_size
    )
    cases, skipped = build_cases(
        recent_docs, args.video_sample_size, args.owner_sample_size
    )
    results = evaluate_cases(base_url, auth_header, ssl_context, args.index, cases)

    report = {
        "index": args.index,
        "recent_fetch_size": args.recent_fetch_size,
        "video_sample_size": args.video_sample_size,
        "owner_sample_size": args.owner_sample_size,
        "skipped": skipped,
        "case_count": len(results),
        "summary": build_report_summary(results),
        "cases": results,
    }

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    bad_case_output = args.bad_case_output or f"{output_path}.summary.md"
    Path(bad_case_output).write_text(
        "\n".join(build_bad_case_lines(report)) + "\n", encoding="utf-8"
    )
    print(
        json.dumps(
            {
                "output": str(output_path),
                "bad_case_output": bad_case_output,
                "case_count": len(results),
                "summary": report["summary"],
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
