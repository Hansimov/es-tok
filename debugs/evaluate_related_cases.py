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
    for doc in recent_docs[:video_sample_size]:
        bvid = doc.get("bvid")
        if not bvid:
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
    seen_mids = set()
    for doc in recent_docs:
        owner = doc.get("owner") or {}
        mid = owner.get("mid")
        if not mid or mid in seen_mids:
            continue
        seen_mids.add(mid)
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

    return cases + owner_cases


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
            case_result["responses"].append(
                {
                    "relation": query["relation"],
                    "request": query["body"],
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
    cases = build_cases(recent_docs, args.video_sample_size, args.owner_sample_size)
    results = evaluate_cases(base_url, auth_header, ssl_context, args.index, cases)

    report = {
        "index": args.index,
        "recent_fetch_size": args.recent_fetch_size,
        "video_sample_size": args.video_sample_size,
        "owner_sample_size": args.owner_sample_size,
        "case_count": len(results),
        "cases": results,
    }

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(
        json.dumps(
            {"output": str(output_path), "case_count": len(results)}, ensure_ascii=False
        )
    )


if __name__ == "__main__":
    main()
