#!/usr/bin/env python3

import argparse
import base64
import json
import ssl
import time
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path


DEFAULT_SOURCE_FIELDS = [
    "bvid",
    "title",
    "owner.mid",
    "owner.name",
    "tags",
    "desc",
    "tid",
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


def topic_tokens(*values) -> set[str]:
    tokens = set()
    for value in values:
        for token in extract_topic_tokens(value):
            if looks_like_useful_token(token):
                tokens.add(token)
    return tokens


def extract_topic_tokens(value) -> list[str]:
    normalized = normalize_seed_text(value)
    if not normalized:
        return []

    parts = []
    buffer = []
    for ch in normalized:
        if ch.isalnum() or ord(ch) > 127:
            buffer.append(ch)
            continue
        if buffer:
            parts.append("".join(buffer))
            buffer = []
    if buffer:
        parts.append("".join(buffer))

    tokens = []
    for part in parts:
        if len(part) < 2:
            continue
        tokens.append(part)
        if any(ord(ch) > 127 for ch in part) and len(part) <= 12:
            max_width = min(4, len(part))
            for width in range(2, max_width + 1):
                for index in range(0, len(part) - width + 1):
                    tokens.append(part[index : index + width])
    return tokens


def seed_has_useful_signal(seed: dict, include_owner_name: bool = True) -> bool:
    owner = seed.get("owner") or {}
    candidates = [seed.get("title", ""), seed.get("tags", ""), seed.get("desc", "")]
    if include_owner_name:
        candidates.append(owner.get("name", ""))
    for value in candidates:
        for token in extract_topic_tokens(value):
            if looks_like_useful_token(token):
                return True
    return False


def top_items(response: dict):
    if "videos" in response:
        return response.get("videos", [])
    if "owners" in response:
        return response.get("owners", [])
    return []


def percentile(values: list[float], ratio: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round((len(ordered) - 1) * ratio))))
    return round(ordered[index], 2)


def summarize_relation(
    seed: dict, relation: str, request: dict, response: dict, latency_ms: float
):
    items = top_items(response)
    summary = {
        "result_count": len(items),
        "latency_ms": latency_ms,
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
        seed_tokens = topic_tokens(seed.get("sample_title", ""), seed.get("name", ""))
        top_titles = [normalize_seed_text(item.get("title", "")) for item in items[:3]]
        if (
            seed_tokens
            and top_titles
            and not any(
                any(token and token in title for token in seed_tokens)
                for title in top_titles
            )
        ):
            summary["notes"].append("weak_topic_overlap")
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
    latency_by_relation = defaultdict(list)
    for case in cases:
        for response_entry in case["responses"]:
            relation = response_entry["relation"]
            relation_summary = response_entry["summary"]
            bucket = summary["by_relation"].setdefault(
                relation,
                {
                    "cases": 0,
                    "empty": 0,
                    "flagged": 0,
                    "avg_result_count": 0.0,
                    "avg_latency_ms": 0.0,
                    "p95_latency_ms": 0.0,
                },
            )
            bucket["cases"] += 1
            bucket["avg_result_count"] += relation_summary["result_count"]
            bucket["avg_latency_ms"] += relation_summary["latency_ms"]
            latency_by_relation[relation].append(relation_summary["latency_ms"])
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

    for relation, bucket in summary["by_relation"].items():
        if bucket["cases"]:
            bucket["avg_result_count"] = round(
                bucket["avg_result_count"] / bucket["cases"], 2
            )
            bucket["avg_latency_ms"] = round(
                bucket["avg_latency_ms"] / bucket["cases"], 2
            )
            bucket["p95_latency_ms"] = percentile(latency_by_relation[relation], 0.95)
    return summary


def format_top_items(case: dict, relation: str) -> list[str]:
    for response in case["responses"]:
        if response["relation"] != relation:
            continue
        items = top_items(response["response"])[:3]
        lines = []
        for item in items:
            if relation.startswith("related_videos_"):
                lines.append(
                    f"{item.get('bvid', '')} | {item.get('title', '')} | owner={item.get('owner_name', '')} | score={item.get('score', 0.0)}"
                )
            else:
                lines.append(
                    f"{item.get('mid', '')} | {item.get('name', '')} | score={item.get('score', 0.0)} | doc_freq={item.get('doc_freq', 0)}"
                )
        return lines
    return []


def build_bad_case_lines(report: dict):
    lines = [
        f"# Related Report Summary: {report['index']}",
        "",
        f"- case_count: {report['case_count']}",
        f"- recent_fetch_size: {report['recent_fetch_size']}",
        f"- video_sample_size: {report['video_sample_size']}",
        f"- owner_sample_size: {report['owner_sample_size']}",
        f"- tid_count: {report['tid_count']}",
        "",
        "## By Relation",
    ]
    for relation, stats in report["summary"]["by_relation"].items():
        lines.append(
            f"- {relation}: cases={stats['cases']} empty={stats['empty']} flagged={stats['flagged']} avg_result_count={stats['avg_result_count']} avg_latency_ms={stats['avg_latency_ms']} p95_latency_ms={stats['p95_latency_ms']}"
        )

    lines.extend(["", "## Sampled Tids"])
    for tid, count in report.get("sampled_tid_counts", [])[:24]:
        lines.append(f"- tid={tid}: samples={count}")

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
        for case in noted_cases[:40]:
            lines.append(
                f"- {case['label']} | {case['relation']} | notes={', '.join(case['notes'])}"
            )

    lines.extend(["", "## Manual Review Pack"])
    review_cases = []
    seen = set()
    for case in report["cases"]:
        if any(
            response["summary"]["anomalies"] or response["summary"]["notes"]
            for response in case["responses"]
        ):
            review_cases.append(case)
            seen.add(case["label"])
        if len(review_cases) >= 16:
            break
    if len(review_cases) < 24:
        for case in report["cases"]:
            if case["label"] in seen:
                continue
            review_cases.append(case)
            if len(review_cases) >= 24:
                break
    for case in review_cases:
        seed = case["seed"]
        lines.append(f"### {case['label']}")
        lines.append(
            f"- seed_tid={seed.get('tid', '')} seed_title={seed.get('title', seed.get('sample_title', ''))} seed_owner={(seed.get('owner') or {}).get('name', seed.get('name', ''))}"
        )
        for relation in [
            "related_videos_by_videos",
            "related_owners_by_videos",
            "related_videos_by_owners",
            "related_owners_by_owners",
        ]:
            top_lines = format_top_items(case, relation)
            if not top_lines:
                continue
            lines.append(f"- {relation}:")
            for top_line in top_lines:
                lines.append(f"  - {top_line}")
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
    started = time.perf_counter()
    with urllib.request.urlopen(request, context=ssl_context, timeout=60) as response:
        body = json.loads(response.read().decode("utf-8"))
    elapsed_ms = round((time.perf_counter() - started) * 1000.0, 2)
    return body, elapsed_ms


def default_doc_query():
    return {"bool": {"filter": [{"exists": {"field": "title.words"}}]}}


def fetch_docs(
    base_url: str,
    auth_header: str | None,
    ssl_context,
    index_name: str,
    size: int,
    sort_clause: list[dict],
    query: dict | None = None,
):
    payload = {
        "size": size,
        "sort": sort_clause,
        "_source": DEFAULT_SOURCE_FIELDS,
        "query": query or default_doc_query(),
    }
    response, _ = request_json(
        base_url, f"/{index_name}/_search", auth_header, ssl_context, payload
    )
    return [hit.get("_source", {}) for hit in response.get("hits", {}).get("hits", [])]


def fetch_top_tids(
    base_url: str,
    auth_header: str | None,
    ssl_context,
    index_name: str,
    size: int,
):
    payload = {
        "size": 0,
        "query": default_doc_query(),
        "aggs": {"top_tids": {"terms": {"field": "tid", "size": size}}},
    }
    response, _ = request_json(
        base_url, f"/{index_name}/_search", auth_header, ssl_context, payload
    )
    return [
        bucket.get("key")
        for bucket in response.get("aggregations", {})
        .get("top_tids", {})
        .get("buckets", [])
    ]


def append_unique_docs(target: list[dict], docs: list[dict], seen_bvids: set[str]):
    for doc in docs:
        bvid = doc.get("bvid")
        if not bvid or bvid in seen_bvids:
            continue
        seen_bvids.add(bvid)
        target.append(doc)


def collect_docs(
    base_url: str,
    auth_header: str | None,
    ssl_context,
    index_name: str,
    fetch_size: int,
    tid_count: int,
    docs_per_tid: int,
):
    combined = []
    seen_bvids = set()
    append_unique_docs(
        combined,
        fetch_docs(
            base_url,
            auth_header,
            ssl_context,
            index_name,
            fetch_size,
            [{"insert_at": "desc"}],
        ),
        seen_bvids,
    )
    append_unique_docs(
        combined,
        fetch_docs(
            base_url,
            auth_header,
            ssl_context,
            index_name,
            fetch_size,
            [{"stat.view": "desc"}],
        ),
        seen_bvids,
    )
    top_tids = fetch_top_tids(base_url, auth_header, ssl_context, index_name, tid_count)
    for tid in top_tids:
        tid_query = {
            "bool": {
                "filter": [
                    {"exists": {"field": "title.words"}},
                    {"term": {"tid": tid}},
                ]
            }
        }
        append_unique_docs(
            combined,
            fetch_docs(
                base_url,
                auth_header,
                ssl_context,
                index_name,
                docs_per_tid,
                [{"insert_at": "desc"}],
                tid_query,
            ),
            seen_bvids,
        )
        append_unique_docs(
            combined,
            fetch_docs(
                base_url,
                auth_header,
                ssl_context,
                index_name,
                docs_per_tid,
                [{"stat.view": "desc"}],
                tid_query,
            ),
            seen_bvids,
        )
    return combined, top_tids


def round_robin_select(
    grouped_docs: dict[int, list[dict]], target_size: int, key_field: str
):
    selected = []
    seen_keys = set()
    tid_items = sorted(
        [(tid, docs) for tid, docs in grouped_docs.items() if docs],
        key=lambda item: (-len(item[1]), item[0]),
    )
    while tid_items and len(selected) < target_size:
        next_items = []
        for tid, docs in tid_items:
            picked = None
            while docs:
                candidate = docs.pop(0)
                key = candidate.get(key_field)
                if key and key not in seen_keys:
                    picked = candidate
                    seen_keys.add(key)
                    break
            if picked is not None:
                selected.append(picked)
                if len(selected) >= target_size:
                    break
            if docs:
                next_items.append((tid, docs))
        tid_items = next_items
    return selected


def build_cases(recent_docs, video_sample_size: int, owner_sample_size: int):
    video_groups = defaultdict(list)
    owner_groups = defaultdict(list)
    skipped_video_seeds = 0
    skipped_owner_seeds = 0
    seen_owner_mids = set()

    for doc in recent_docs:
        tid = int(doc.get("tid") or -1)
        if tid < 0:
            continue
        bvid = doc.get("bvid")
        if bvid and seed_has_useful_signal(doc, include_owner_name=False):
            video_groups[tid].append(doc)
        elif bvid:
            skipped_video_seeds += 1

        owner = doc.get("owner") or {}
        mid = owner.get("mid")
        if not mid or mid in seen_owner_mids:
            continue
        seen_owner_mids.add(mid)
        if seed_has_useful_signal(doc, include_owner_name=True):
            owner_groups[tid].append(doc)
        else:
            skipped_owner_seeds += 1

    video_docs = round_robin_select(video_groups, video_sample_size, "bvid")
    owner_docs = round_robin_select(owner_groups, owner_sample_size, "bvid")

    cases = []
    sampled_tid_counter = Counter()
    for doc in video_docs:
        sampled_tid_counter[int(doc.get("tid") or -1)] += 1
        bvid = doc.get("bvid")
        cases.append(
            {
                "label": f"video:{bvid}",
                "seed": doc,
                "responses": [],
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

    for doc in owner_docs:
        owner = doc.get("owner") or {}
        mid = owner.get("mid")
        sampled_tid_counter[int(doc.get("tid") or -1)] += 1
        cases.append(
            {
                "label": f"owner:{mid}",
                "seed": {
                    "mid": mid,
                    "name": owner.get("name", ""),
                    "sample_bvid": doc.get("bvid", ""),
                    "sample_title": doc.get("title", ""),
                    "tid": doc.get("tid"),
                },
                "responses": [],
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

    return cases, {
        "skipped_video_seeds": skipped_video_seeds,
        "skipped_owner_seeds": skipped_owner_seeds,
        "sampled_tid_counts": sampled_tid_counter.most_common(),
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
            response, latency_ms = request_json(
                base_url,
                f"/{index_name}/_es_tok/{query['relation']}",
                auth_header,
                ssl_context,
                query["body"],
            )
            summary = summarize_relation(
                case["seed"], query["relation"], query["body"], response, latency_ms
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
    parser.add_argument("--video-sample-size", type=int, default=100)
    parser.add_argument("--owner-sample-size", type=int, default=100)
    parser.add_argument("--recent-fetch-size", type=int, default=1200)
    parser.add_argument("--tid-count", type=int, default=48)
    parser.add_argument("--docs-per-tid", type=int, default=12)
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
    recent_docs, top_tids = collect_docs(
        base_url,
        auth_header,
        ssl_context,
        args.index,
        args.recent_fetch_size,
        args.tid_count,
        args.docs_per_tid,
    )
    cases, sampling = build_cases(
        recent_docs, args.video_sample_size, args.owner_sample_size
    )
    results = evaluate_cases(base_url, auth_header, ssl_context, args.index, cases)

    report = {
        "index": args.index,
        "recent_fetch_size": args.recent_fetch_size,
        "video_sample_size": args.video_sample_size,
        "owner_sample_size": args.owner_sample_size,
        "tid_count": len(top_tids),
        "top_tids": top_tids,
        "sampled_tid_counts": sampling["sampled_tid_counts"],
        "skipped": {
            "skipped_video_seeds": sampling["skipped_video_seeds"],
            "skipped_owner_seeds": sampling["skipped_owner_seeds"],
        },
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
                "tid_count": len(top_tids),
                "summary": report["summary"],
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
