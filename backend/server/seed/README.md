# Seed data

CSV files the server loads at start-up (they are packaged into the server under `seed/`).

| File | Served by | Stored in |
| --- | --- | --- |
| `sender_ids.csv` | `GET /v1/sender-ids/{countryIso}?since=` | `sender_ids` table (upserted on start) |
| `otp_app_hashes.csv` | `GET /v1/otp-sources?since=` (`appHashes`) | memory |
| `verification_ranges.csv` | `GET /v1/otp-sources?since=` (`verificationRanges`) | memory |

Rules:

- **Example rows only** until Phase 9: every brand, package and provider here is fake and says so.
- Lines starting with `#` are comments. No quoting: values must not contain commas or quotes.
- `updated_at` is an ISO-8601 instant. Apps sync with `?since=<epoch ms>` and get rows with a later `updated_at`,
  so change `updated_at` whenever a row changes.
- The server refuses to start if a file is malformed (`SeedDataTest` checks these files in CI).
