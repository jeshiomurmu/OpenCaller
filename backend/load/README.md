# Load tests (k6)

TC-API-011 in `docs/testing.md`: the bucket lookup must stay under 50 ms p95 server time with 1M numbers at
200 requests per second. Results are recorded in `docs/testing.md` → "TC-API-011 results".

| File | Purpose |
| --- | --- |
| `seed.sql`, `seed.sh` | 1,000,000 synthetic +880 numbers in the lookup index (20 % with reports) and 100,000 named users. Development stacks only. |
| `bucket.js` | k6 scenario: 10 s warm-up at 50 rps, then 200 rps for 60 s on random 16-bit buckets; threshold p95 < 50 ms. |
| `server_p95.py` | Server-side p50/p95/p99 of the bucket route from two scrapes of the internal `/metrics`. |

k6 runs from its Docker image (`grafana/k6:2.3.0`), so nothing is installed on the host. See `docs/testing.md` for
the exact commands.
