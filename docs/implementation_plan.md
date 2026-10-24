# Implementation plan — OpenCaller

16 phases, in order. Each phase has a checklist and exit criteria; a phase is done only when every box is ticked **and** the exit criteria pass. The matching build prompt for each phase is in `docs/prompts.md` (P-00 … P-15).

Rough effort for one full-time developer working with an AI agent: Phases 0–7 ≈ 10–14 weeks (MVP), 8–12 ≈ 8–10 weeks, 13–15 ≈ 6–8 weeks.

| Phase | Name | PRD features | Release |
| --- | --- | --- | --- |
| 0 | Repo, tooling, CI | — | — |
| 1 | Core foundations | — | — |
| 2 | Onboarding, roles, permissions | — | — |
| 3 | Call screening + local blocking | F-ID-01 (local), F-ID-07, F-PR-02, 04–07, 09 | — |
| 4 | Backend MVP | server for F-PR-01, 08, 16, 17, F-ID-03, F-SO-01 | — |
| 5 | Sync, live lookup, reporting, search | F-ID-01–03, F-PR-01, 08, 16 | v0.1 alpha |
| 6 | Dialer, in-call, call log, contacts | F-CM-01–04, 12 | — |
| 7 | SMS app, smart inbox, OTP | F-ID-08, F-PR-10–12, F-CM-05–08, 11 | — |
| 8 | Profiles and social | F-SO-01–07 | — |
| 9 | Business and sender registries | F-ID-04, F-ID-09, F-BZ-01–03 | v0.5 beta |
| 10 | Supporter tier and utilities | F-PR-03, F-UT-01, 02, 05–08, F-ID-05 | — |
| 11 | VoIP, Flash, video caller ID | F-CM-09, 10, F-ID-06 | — |
| 12 | Family protection, scam feed, business dashboard | F-PR-14, 15, F-BZ-04 | v1.0 |
| 13 | Recording line, AI assistant, voice scanner | F-UT-03, 04, F-PR-13 | v1.x |
| 14 | Hardening | all | — |
| 15 | Release and deployment | all | each release |

---

## Phase 0 — Repo, tooling, CI

- [ ] `git init`, `main` branch, push to GitHub `jeshiomurmu/opencaller` (private until v0.1)
- [x] Add `LICENSE` (GPL-3.0 full text from gnu.org) and `CHANGELOG.md`
- [x] Create Android Gradle project in `android/` with version catalog and `build-logic` convention plugins
- [x] Create empty modules from `architecture.md` §3 and wire them in `settings.gradle.kts`
- [x] Flavors `foss` and `play`; `applicationId io.github.jeshiomurmu.opencaller`; `minSdk 29`, `compileSdk 36`, `targetSdk 36`
- [x] Spotless (ktlint), Detekt, Android Lint configured; `./gradlew check` green
- [x] Ktor server skeleton in `backend/server` with `/health`
- [x] `infra/docker/compose.yml`: postgres, redis, minio, server
- [x] GitHub Actions `ci.yml`: Android check + assemble both flavors; backend check
- [x] Review drafted ADRs 0001 (consent-first data), 0002 (Ktor backend), 0003 (GPL-3.0) in `docs/decisions/`

**Exit:** CI green on `main`; `assembleFossDebug` installs and opens an empty app on an emulator; `curl localhost:8080/health` returns 200.

## Phase 1 — Core foundations

- [x] `core:common`: `AppResult`, `AppError`, `DispatcherProvider`, `Clock`, `RedactingTree`
- [x] `core:model`: `PhoneNumber`, `CallerInfo`, `NameSource`, `SpamVerdict`, `BlockRule`, `Decision`, `MessageCategory`, `OtpInfo`
- [x] `core:phonenumber`: normalizer (E.164, default region from SIM/locale), number type, country, bucket hasher; unit tests incl. BD, IN, US, UK, NG numbers
- [x] `core:database`: Room + SQLCipher, all entities from `architecture.md` §6, DAOs, schema export, migration test harness
- [x] `core:datastore`: `UserSettings` proto (blocking toggles, thresholds, overlay, announce, theme)
- [x] `core:designsystem`: theme, tokens from `design.md`, typography with Noto Sans, base components; previews
- [x] `core:testing`: fakes, `MainDispatcherRule`, fixtures
- [x] App shell: `MainActivity`, bottom navigation (Calls, Messages, Contacts, Protection, Me) with placeholder screens

**Exit:** unit tests ≥ 90% line coverage on `core:phonenumber`; DB opens encrypted (verified by a test that the file header is not `SQLite format 3`); app shell navigates in light/dark.

## Phase 2 — Onboarding, roles, permissions

- [x] Welcome + privacy explainer screens (what leaves the phone, what never does)
- [x] `RoleManagerHelper`: query/request `ROLE_CALL_SCREENING`, `ROLE_DIALER`, `ROLE_SMS`
- [x] Permission explainers + requests per `architecture.md` §5 (only after the matching role)
- [x] Overlay permission flow (`SYSTEM_ALERT_WINDOW`)
- [x] Notification permission (API 33+)
- [x] Onboarding state persisted; re-entry from Settings → Permissions status screen
- [x] Handle "role revoked later" (re-check on resume, banner)

**Exit:** on a fresh emulator, onboarding grants call-screening role; denying each permission shows the degraded-mode banner, never a crash (TC-ONB-001…008).

## Phase 3 — Call screening + local blocking

- [x] `OpenCallerScreeningService` registered with `BIND_SCREENING_SERVICE`
- [x] `ScreeningEngine` with decision order from `architecture.md` §4.1, 1,500 ms budget, fail-open
- [x] Contact check via ContactsProvider `PhoneLookup`
- [x] Rules: exact, prefix, regex (with safe timeout), country; allow beats block
- [x] Toggles: block hidden, foreign (with allowed countries), non-contacts
- [x] Bundled seed spam list (empty in repo; dev fixture for tests) and `spam_list` lookup
- [x] STIR/SHAKEN verdict read on API 30+
- [x] Caller card overlay (`OverlayController`) for screening-only mode; after-call notification
- [x] Blocked-calls log screen + rules editor UI (`feature:blocking`)
- [x] `screening_event` logging + debug screen

**Exit:** with `adb emu gsm call`, every row in `testing.md` TC-BLK-* passes; p95 decision latency < 50 ms for local decisions (measured in debug screen); screening exception test proves fail-open.

## Phase 4 — Backend MVP

- [x] Flyway `V1__init.sql` from `architecture.md` §7
- [x] Auth: `/v1/auth/start|verify|refresh`; OTP provider interface with `LogOtpProvider` (dev) and one real provider adapter
- [x] Rate limiting (Redis)
- [x] Reports API; reporter trust defaults
- [x] Scoring job (Bayesian, §9) running every 15 min; unit tests with fixtures
- [x] List builder: per-country full + delta files to MinIO, `list_versions`, manifest endpoint
- [x] Bucket lookup endpoint with ETag
- [x] Explicit search endpoint with quota
- [x] Disputes + unlist endpoints (unlist works without the app: simple HTML form served by Ktor)
- [x] `/v1/sender-ids` and `/v1/otp-sources` (seeded from `backend/server/seed/`)
- [x] OpenAPI spec; problem+json errors; Prometheus metrics
- [x] `scripts/smoke.sh` exercising the whole API against compose

**Exit:** `./gradlew check` green incl. Testcontainers; smoke script passes; bucket endpoint p95 < 50 ms server time on 1M seeded numbers (load test with k6 or Gatling documented in testing.md).

## Phase 5 — Sync, live lookup, reporting, search

- [ ] `core:network` client, auth interceptor, token refresh, certificate pinning (`play`)
- [ ] Sign-up flow in onboarding (optional; app works signed-out for blocking)
- [ ] `SpamListSyncWorker` (daily, unmetered by default, delta apply, sha256 verify)
- [ ] Bucket lookup in `ScreeningEngine` step 10 with 800 ms timeout + cache
- [ ] Report spam/not-spam UI from after-call card and blocked log; `ReportUploadWorker`
- [ ] Number search screen with quota display
- [ ] Dispute + unlist links in app
- [ ] Caller card shows source chip and report count

**Exit:** end-to-end: report a number on device A → after scoring + list build, device B auto-blocks it (TC-E2E-001). Tag **v0.1.0-alpha**, GitHub pre-release with `foss` APK.

## Phase 6 — Dialer, in-call, call log, contacts

- [x] Default dialer role handling; `InCallService` (`BIND_INCALL_SERVICE`, `android.telecom.IN_CALL_SERVICE_UI` meta-data)
- [x] In-call UI: answer/decline (incl. lock screen via full-screen intent), mute, speaker, Bluetooth, keypad (DTMF), hold, add/merge/swap, caller card
- [x] Dialer: keypad, T9 search, dual-SIM picker, voicemail shortcut, `tel:` intent handling, MMI/USSD codes passthrough
- [x] Call log: grouped list, filters (missed, blocked, spam), delete, search
- [x] Contacts: list, detail, create/edit, favourites (ContactsProvider; consider forking Fossify Phone UI patterns, keeping GPL notices)
- [x] After-call card as full activity when default dialer; quick-reply templates
- [x] Emergency-number handling verified (never intercepted)

**Exit:** TC-DIAL-*, TC-INCALL-*, TC-LOG-* pass on emulator and one physical dual-SIM phone.

## Phase 7 — SMS app, smart inbox, OTP

- [ ] Default SMS role components: `SMS_DELIVER` receiver, `WAP_PUSH_DELIVER` receiver, `RESPOND_VIA_MESSAGE` service, `SENDTO` activity
- [ ] Conversations, thread, compose, MMS (images), group MMS, dual-SIM send, delivery reports
- [ ] `MessagePipeline` (architecture §4.4)
- [ ] `SenderResolver` incl. registered-header parsing (e.g. India `XX-HEADER-T` format), domain-bound code, app hash, verification ranges
- [ ] `OtpExtractor` with test corpus (≥ 300 real-format samples across BD/IN/US, anonymised)
- [ ] `SmsClassifier` LiteRT model v0 (trained in `ml/sms-classifier`, exported `.tflite` in assets) + rule fallback
- [ ] Smart inbox tabs, spam folder, inbox cleaner
- [ ] OTP card + notification (code hidden on lock screen), auto-delete option
- [ ] `OtpSafetyGuard` + full-screen warning; flash-call detection
- [ ] Unrequested-OTP / OTP-burst alert
- [ ] Transaction parser (top banks for BD/IN/US)
- [ ] `LinkChecker` (lookalike domains + hash-prefix bad-URL list from server)
- [ ] Scheduled SMS

**Exit:** TC-SMS-*, TC-OTP-* pass; OTP extraction precision ≥ 98% and recall ≥ 95% on the held-out corpus; classifier macro-F1 reported in testing.md.

## Phase 8 — Profiles and social

- [ ] My profile: name, photo, visibility (Everyone / Registered users only / Nobody). No "people I call" option: it would require sending call pairs to the server, which breaks the privacy invariants
- [ ] Who viewed my profile, who searched for me (supporter-gated)
- [ ] Incognito mode
- [ ] Contact requests
- [ ] Supporter / Gold badge rendering
- [ ] Delete account flow

**Exit:** TC-SOC-* pass; privacy review of viewer/searcher logging recorded in `docs/decisions/`.

## Phase 9 — Business and sender registries

- [ ] Business claim flow (OTP or flash call to the business number + document upload to MinIO)
- [ ] Admin review web (Ktor + simple server-rendered HTML) with audit log
- [ ] Business profile display (name, logo, category, call reason, verified badge)
- [ ] Sender-ID importers framework + at least one real registry importer where data access is granted; manual CSV import otherwise
- [ ] App-hash table + flash-call ranges curation tooling
- [ ] Name-source chips everywhere (F-ID-09)

**Exit:** TC-BIZ-* pass. Tag **v0.5.0-beta**: GitHub Release, F-Droid submission opened, Play closed testing.

## Phase 10 — Supporter tier and utilities

- [ ] `EntitlementProvider`: `play` = Play Billing (subscriptions: Supporter, Gold, Family) + server verification; `foss` = self-host flag / donation
- [ ] Extended spam list (F-PR-03)
- [ ] Ghost Call (full-screen intent fake incoming call UI)
- [ ] Announce Caller (TextToSpeech, headset-only option)
- [ ] Backup/restore: `play` Drive appDataFolder, `foss` encrypted file via SAF
- [ ] Themes incl. Gold
- [ ] WhatsApp/Telegram/Signal caller ID via `NotificationListenerService`

**Exit:** TC-UT-*, TC-BILL-* pass (Play Billing tested with license testers).

## Phase 11 — VoIP, Flash, video caller ID

- [ ] LiveKit server + TURN in compose; `/v1/voip/token`
- [ ] App-to-app calls with LiveKit SDK + Jetpack Telecom (`core-telecom`) integration; push-to-ring via FCM/UnifiedPush
- [ ] Flash messages
- [ ] Video caller ID upload (≤ 10 s, transcoded server-side) and playback in in-call UI

**Exit:** TC-VOIP-* pass across two networks (Wi-Fi ↔ mobile data); call setup < 3 s p95.

## Phase 12 — Family protection, scam feed, business dashboard

- [ ] Family groups, invites, explicit member consent screen
- [ ] Alerts: OTP-during-call, blocked-scam summary (counts only)
- [ ] Scam feed cards per country (server-curated)
- [ ] Business dashboard web (F-BZ-04): verified businesses manage profile and call reasons and see searches, reports and disputes for their numbers (server-side data only; no call-outcome telemetry)

**Exit:** TC-FAM-* pass. Tag **v1.0.0**: Play production rollout (staged 10% → 100%), F-Droid.

## Phase 13 — Recording line, AI assistant, voice scanner

- [ ] LiveKit SIP + SIP trunk config docs (provider-agnostic) in `infra/deploy`
- [ ] Line allocation API; per-country legal switch for recording
- [ ] Recording: merge flow UI, beep, download, server deletion ≤ 1 h, local playback, transcript + summary (optional)
- [ ] AI assistant: Python LiveKit agent (STT → LLM → TTS), guided call-forwarding setup with MMI codes and carrier notes, live transcript UI, pick-up/reply/block actions
- [ ] Voice scanner: anti-spoofing service in `ml/voice-spoof`, agent integration, warning UI with uncertainty text

**Exit:** TC-REC-*, TC-AST-*, TC-SCAN-* pass on a real SIM in one launch country; cost per screened minute measured and recorded.

## Phase 14 — Hardening

- [ ] Accessibility audit (TalkBack walkthrough of every screen, font scale 200%)
- [ ] Performance: Baseline Profiles, Macrobenchmark for cold start and screening; battery test 24 h
- [ ] Security review: OWASP MASVS checklist, dependency audit, pinning tests
- [ ] Privacy review: data inventory matches `architecture.md` §10; privacy policy + terms published
- [ ] Localization: Bengali, Hindi, English first; RTL check (Arabic)
- [ ] Crash-free ≥ 99.5% in beta cohort

**Exit:** all P0/P1 test cases pass; no open Severity-1/2 bugs.

## Phase 15 — Release and deployment

- [ ] Release signing via GitHub Actions secrets; `release.yml` builds signed APKs (`foss`, `play`) and AAB (`play`) on tag
- [ ] GitHub Release with changelog and checksums
- [ ] F-Droid metadata (`fastlane/metadata/android/…`) and reproducible-build notes
- [ ] Play Console: listing, Data safety form, SMS/Call Log permissions declaration, target API 36, staged rollout
- [ ] Backend deploy: GHCR images, `infra/deploy` VPS guide, backups (pg_dump nightly to off-site), TLS via Caddy
- [ ] Status page / uptime checks

**Exit:** a tagged release produces all artifacts automatically; a fresh VPS can be brought up from the deploy guide in < 1 hour.
