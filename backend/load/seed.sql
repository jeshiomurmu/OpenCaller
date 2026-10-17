-- TC-API-011 load-test data: 1,000,000 synthetic Bangladeshi mobile numbers (+88017 00000001 … +88017 01000000)
-- in the lookup index, 20 % of them with spam reports, plus 100,000 registered users with names (mixed
-- visibility) among them. Synthetic data only; never run against production. Idempotent (ON CONFLICT DO NOTHING).
-- bucket_16 = first 16 bits of SHA-256(UTF-8 E.164), the same as BucketHasher (docs/architecture.md §4.6).
\set ON_ERROR_STOP on
\timing on

INSERT INTO number_reputation (e164, country_code, bucket_16, spam_score, category, report_count, not_spam_count,
                               last_report_at, model_version, updated_at)
SELECT n.e164,
       880,
       (get_byte(n.h, 0) << 8) | get_byte(n.h, 1),
       CASE WHEN n.i % 5 = 0 THEN 0.3 + (n.i % 70) / 100.0 ELSE 0 END,
       CASE WHEN n.i % 5 = 0 THEN (ARRAY['scam', 'telemarketing', 'loan', 'robocall'])[1 + n.i % 4] END,
       CASE WHEN n.i % 5 = 0 THEN 1 + n.i % 200 ELSE 0 END,
       CASE WHEN n.i % 5 = 0 THEN n.i % 3 ELSE 0 END,
       CASE WHEN n.i % 5 = 0 THEN now() - (n.i % 90) * interval '1 day' END,
       'load-test',
       now()
FROM (SELECT i, e164, sha256(convert_to(e164, 'UTF8')) AS h
      FROM (SELECT i, '+88017' || lpad(i::text, 8, '0') AS e164 FROM generate_series(1, 1000000) AS i) AS g) AS n
ON CONFLICT (e164) DO NOTHING;

INSERT INTO users (id, e164, verified_at, display_name, visibility)
SELECT gen_random_uuid(),
       '+88017' || lpad(i::text, 8, '0'),
       now(),
       'Load Test User ' || i,
       (ARRAY['everyone', 'registered_only', 'nobody'])[1 + i % 3]::visibility
FROM generate_series(1, 1000000, 10) AS i
ON CONFLICT (e164) DO NOTHING;

ANALYZE number_reputation;
ANALYZE users;

SELECT count(*) AS indexed_numbers, count(*) FILTER (WHERE report_count > 0) AS with_reports
FROM number_reputation WHERE country_code = 880;
SELECT round(avg(c), 1) AS avg_rows_per_bucket, max(c) AS max_rows_per_bucket
FROM (SELECT count(*) AS c FROM number_reputation WHERE country_code = 880 GROUP BY bucket_16) AS b;
