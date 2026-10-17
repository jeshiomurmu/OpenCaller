# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0-alpha] - 2026-10-17

First alpha. Blocking works on the phone without any server; this build has no server configured, so sign-in, reports,
search and spam-list updates say "Not connected to a server".

### Added

- App (Phase 5): network layer (`core:network`: Retrofit, OkHttp, kotlinx.serialization) with token refresh, server
  address from the build (none in release builds unless set), certificate pinning in Play release builds and HTTPS
  required for release builds.
- Optional sign-in with your phone number (number → code by SMS → done) on the last onboarding step and in Me →
  Account. Sign-in tokens are encrypted with their own Android Keystore key.
- Daily spam-list update (Wi-Fi only unless you allow mobile data, plus "Update now"): only the changes are downloaded
  when possible; every file is checked (SHA-256 and format) and applied in one database transaction, and the old list
  stays if anything is wrong.
- Private live caller ID during a call: only a short code made from the scrambled number is sent (k-anonymous hash
  bucket), the answer is matched on the phone, kept for 24 hours, and never holds up a call for more than 800 ms. It
  can block at your spam threshold; contacts and verified businesses are never blocked.
- Report sheet (spam or not spam, category, comment, "Also block") from the after-call notification and the
  blocked-calls log; reports wait on the phone until you're online and signed in, and respect the server's limits.
- Number search with "n of 50 searches left today" and clear offline, limit and server-error messages.
- "Why am I seeing this?" for caller cards, blocked calls and search results; spam categories in your language.
- "Wrongly marked as spam?" (asks for a review) and "Unlist my number" (opens the server's web form) in Me → Account.
- CI: TC-E2E-001 with two emulators and the local server (device A reports, the server's jobs run, device B updates
  its list and blocks the number, then blocks a second one by live lookup); release workflow that builds the signed
  foss APK on a version tag and publishes it with its SHA-256.

- Server (Phase 4, part 2): spam reports (`POST /v1/reports`, up to 50 per request, 100 per day); a scoring job every
  15 minutes that turns reports into a Bayesian spam score with time decay (verified businesses are never labelled
  spam; they go to human review instead); nightly per-country spam lists with deltas in a compact binary format,
  stored in MinIO and served with checksums (`/v1/lists/{country}/manifest`); private live caller ID by hash bucket
  (`/v1/lookup/buckets/{cc}/{bucket}`) that respects who may see a name; explicit search with 50 searches a day,
  recorded for "who searched me" unless incognito; disputes; unlisting a number through the API or a plain web form at
  `/unlist`; sender-ID and OTP-source registries (example data only); admin endpoints to run jobs now.
- `scripts/smoke.sh` covers the whole API with curl and jq; `backend/load/` has the k6 load test for the bucket lookup
  (200 requests per second on 1M numbers: p95 under 5 ms).
- Server (Phase 4, part 1): PostgreSQL schema v1 (Flyway, as in `docs/architecture.md` §7) with Exposed tables;
  configuration from environment variables only, refusing to start with a list of every missing or invalid value;
  phone sign-in `POST /v1/auth/start|verify|refresh` — 6-digit codes valid 5 minutes with 5 attempts, stored only as a
  keyed hash in Redis and sent by Twilio Programmable Messaging (or written to the log in development); 15-minute JWT
  access tokens and 30-day rotating refresh tokens, where reusing a rotated token signs the device out; rate limits of
  3 codes per hour per number and 10 per hour per address with `Retry-After`; RFC 9457 problem+json errors;
  `X-Request-Id`; JSON logs with phone numbers redacted; Prometheus metrics on an internal port; `openapi.yaml`.
- `scripts/smoke.sh`: API smoke test against the compose stack (run by the CI compose job).
- Call screening and local blocking (Phase 3): `ScreeningEngine` decides every incoming call on the device in the
  documented order (emergency and outgoing calls always allowed; contacts, allow rules, block/silence rules, hidden,
  foreign and non-contact settings, spam list at the user's threshold, reputation cache, STIR/SHAKEN) within a hard
  1,500 ms budget and fails open on timeouts and errors. Rules: exact, prefix, country and time-limited regex (slow
  patterns are rejected in the editor); allow beats block. `OpenCallerScreeningService` answers with reject, silence or
  allow and logs every decision on the device. Caller card over other apps (or a heads-up notification) that closes
  when the call ends, after-call summary with Block, Report or Not spam, and Save contact. Protection tab: blocking
  settings, threshold, allowed countries, rules editor with "Test a number", blocked-calls log with Undo. Debug screen
  (Settings → About, tap the version 7×) with the last 100 decisions and p50/p95 latency. Settings → Permissions &
  roles gains "Call status" (Android 12+).
- CI: `scripts/qa/blocking.sh` runs TC-BLK-002/003/004/006/008/009/012/015 with `adb emu gsm call` on API 29 and 36
  and checks the p95 decision latency.

### Fixed

- Token responses now include `tokenType` (documented as required; the JSON encoder dropped fields with default values).
- TC-ONB-006 on API 36: a deny tap that lands while the permission dialog animates in is retried.

- Onboarding, roles and permissions (Phase 2): Welcome and privacy screens, then call screening role, contacts and
  notification requests, each behind an explainer, and optional extras (default phone/SMS app, caller ID over other
  apps) in at most five steps; progress survives rotation and process death. Settings → Permissions & roles status
  screen with Fix actions; app-wide banner while call screening is off; Calls tab card for the default phone app.
  `RoleManagerHelper`, `PermissionPolicy` (call log/phone permissions only with the dialer role, SMS only with the
  SMS role) and a pass-through `CallScreeningService` so the role can be granted.
- CI: instrumented onboarding tests (TC-ONB-001/002/003/005/006/007/008) on API 29 and 36; the emulator smoke test
  walks onboarding and checks that it resumes after the process is killed.
- Core foundations (Phase 1): `core:common` (`AppResult`, `AppError`, `DispatcherProvider`, `Clock`, log redaction),
  `core:model` domain types, `core:phonenumber` (libphonenumber normalizer, bucket hasher with vectors shared with the
  server), SQLCipher-encrypted Room database with all entities and DAOs and a Keystore-wrapped passphrase,
  Proto DataStore user settings, design system (color tokens, Noto Sans, spacing/shape tokens, icons),
  `core:ui` components (`SourceChip`, `RiskBanner`, `VerifiedBadge`, `EmptyState`), `core:testing` utilities and an app
  shell with five bottom-bar tabs.
- Server `BucketHasher` matching the app's lookup buckets.
- CI: instrumented database tests and a light/dark tab walk on API 29 and 36 emulators; Room schema and bucket-vector
  consistency checks.

- Android Gradle project (`android/`) with a version catalog, `build-logic` convention plugins
  (`opencaller.android.application`, `.library`, `.feature`, `.compose`, `.hilt`, `.room`, `opencaller.jvm.library`)
  and every `core:*` and `feature:*` module from `docs/architecture.md` §3 as an empty module.
- `app` module with `foss` and `play` flavors (dimension `distribution`), `minSdk 29`, `compileSdk 36`,
  `targetSdk 36`, and a single Compose `MainActivity`.
- Spotless (ktlint), Detekt and Android Lint (warnings as errors, per-module baseline) for the Android project.
- Ktor server (`backend/server/`) with `GET /health`, tests, Spotless, Detekt and a multi-stage, non-root Dockerfile.
- Local development stack in `infra/docker/compose.yml`: PostgreSQL, Redis, MinIO and the server, with healthchecks.
- GitHub Actions CI (`.github/workflows/ci.yml`): Android lint/test/assemble, backend check, emulator smoke test
  (API 29 and 36), compose smoke test and a LICENSE check against gnu.org.
- `infra/docker/minio/Dockerfile`: Chainguard MinIO image plus a static BusyBox for the compose healthcheck.
- `LICENSE` (GPL-3.0), `CHANGELOG.md` and `NOTICE.md`.

[Unreleased]: https://github.com/jeshiomurmu/OpenCaller/compare/v0.1.0-alpha...HEAD
[0.1.0-alpha]: https://github.com/jeshiomurmu/OpenCaller/releases/tag/v0.1.0-alpha
