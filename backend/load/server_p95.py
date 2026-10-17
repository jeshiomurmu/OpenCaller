#!/usr/bin/env python3
"""Server-side latency of the bucket route from two Prometheus scrapes (TC-API-011).

Usage: server_p95.py before.txt after.txt
Reads ktor_http_server_requests_seconds_bucket{route="/v1/lookup/buckets/{cc}/{bucketId}"} from both scrapes
of the internal /metrics endpoint, subtracts them (only requests made between the scrapes count) and prints the
request count and the p50/p95/p99 upper bucket bounds (Micrometer's percentile histogram).
"""
import re
import sys

ROUTE = 'route="/v1/lookup/buckets/{cc}/{bucketId}"'
# Greedy labels: the route label itself contains braces ({cc}).
LINE = re.compile(r'^ktor_http_server_requests_seconds_bucket\{(?P<labels>.*)\} (?P<value>[0-9.eE+-]+)$')


def buckets(path):
    totals = {}
    with open(path, encoding="utf-8") as scrape:
        for line in scrape:
            match = LINE.match(line.strip())
            if not match or ROUTE not in match["labels"] or 'status="200"' not in match["labels"]:
                continue
            le = re.search(r'(?:^|,)le="([^"]+)"', match["labels"]).group(1)  # not throwab-le=
            bound = float("inf") if le == "+Inf" else float(le)
            totals[bound] = totals.get(bound, 0.0) + float(match["value"])
    return totals


def main():
    before, after = buckets(sys.argv[1]), buckets(sys.argv[2])
    diff = sorted((bound, count - before.get(bound, 0.0)) for bound, count in after.items())
    total = diff[-1][1] if diff else 0
    if total <= 0:
        sys.exit("no bucket requests between the two scrapes")
    result = {}
    for quantile in (0.5, 0.95, 0.99):
        result[quantile] = next(bound for bound, count in diff if count >= quantile * total)
    print(f"requests={int(total)} " + " ".join(f"p{int(q * 100)}<={v * 1000:.1f}ms" for q, v in result.items()))


if __name__ == "__main__":
    main()
