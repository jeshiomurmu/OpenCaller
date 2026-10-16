# Build prompts — OpenCaller, from empty repo to GitHub release

Run these in order with Claude Code (or another coding agent) from the **repository root**. One prompt per session is best; start a fresh session for each phase so context stays clean. Each prompt is self-contained: it tells the agent what to read, what to build, how to verify, and how to hand off.

**Before you start**

- Install: JDK 17+, Android Studio (latest stable) with SDK Platform 36 and an API 36 emulator image, Docker, Python 3.11+, `gh` CLI (logged in).
- Have a physical Android 10+ phone with a SIM for `[device]` tests.
- Read `docs/prd.md` once yourself. You are the reviewer: after each prompt, check the diff, run the app, and only then move on.

**How each prompt is structured:** Context → Goal → Tasks → Constraints → Verification → Hand-off. Do not trim the Constraints or Verification blocks; they are what stops feature creep and hallucinated APIs.

---

## Standard preamble (already included in every prompt below)

> Read `CLAUDE.md`, `docs/rules.md`, `docs/phase.md`, and the current phase in `docs/implementation_plan.md`. Work only on that phase. Post a short plan (files, tests, commands) before writing code. Verify every library version and Android API against official sources before using it; record versions in `gradle/libs.versions.toml` and in the "Verified dependency versions" table in `docs/phase.md`. If you can't verify something, stop and ask me. Finish by running the verification commands in `docs/rules.md` §6, updating `docs/phase.md`, and committing with a Conventional Commit message.

---

## P-00 · Phase 0 — Repo, tooling, CI

```
Read CLAUDE.md, docs/rules.md, docs/phase.md, docs/architecture.md §2–3, and Phase 0 in docs/implementation_plan.md. Work only on Phase 0. Post a plan before writing code.

Goal: a buildable, linted, CI-checked monorepo with an empty Android app (two flavors) and a Ktor server that answers /health.

Tasks:
1. Initialize git on branch main. Create branch phase/00-tooling.
2. Add LICENSE with the full GPL-3.0 text downloaded from https://www.gnu.org/licenses/gpl-3.0.txt (do not type it from memory; if you cannot download it, stop and ask me to add it). Add CHANGELOG.md (Keep a Changelog format) and NOTICE.md.
3. In android/: create a Gradle Kotlin DSL project with:
   - gradle/libs.versions.toml containing ONLY dependencies needed for this phase.
   - build-logic/convention with plugins: opencaller.android.application, opencaller.android.library, opencaller.android.feature, opencaller.android.compose, opencaller.android.hilt, opencaller.android.room, opencaller.jvm.library. Use the Now in Android project's build-logic layout as a reference pattern (github.com/android/nowinandroid), but write our own code.
   - Every module listed in docs/architecture.md §3 as an empty module with its namespace io.github.jeshiomurmu.opencaller.<path> and a placeholder Kotlin file so it compiles. core:model and core:common are pure JVM modules.
   - app module: applicationId io.github.jeshiomurmu.opencaller, debug suffix .debug, minSdk 29, compileSdk 36, targetSdk 36, flavor dimension "distribution" with flavors foss and play, a single MainActivity showing a Compose "OpenCaller" text.
   - Gradle wrapper using the Gradle version that the chosen AGP release notes declare compatible.
4. Configure Spotless (ktlint), Detekt (with a detekt.yml in android/config/detekt/), and Android Lint (abortOnError true, warningsAsErrors true for new code via a baseline file).
5. In backend/server/: Gradle Kotlin DSL Ktor server (Netty engine) with kotlinx.serialization, a GET /health route returning {"status":"ok","version":"<git sha or dev>"}, a test using testApplication, and a Dockerfile (multi-stage, non-root user).
6. In infra/docker/compose.yml: postgres, redis, minio (with a healthcheck each) and the server built from backend/server. Add infra/docker/.env.example (no real secrets).
7. Add .github/workflows/ci.yml: on push and pull_request: job "android" (set up JDK 17, Gradle cache via the official Gradle GitHub Action, run ./gradlew spotlessCheck detekt lint testFossDebugUnitTest testPlayDebugUnitTest assembleFossDebug assemblePlayDebug in android/), job "backend" (./gradlew check in backend/server). Look up the current major versions of actions/checkout, actions/setup-java and gradle/actions on their GitHub pages; do not guess.
8. Review the drafted ADRs in docs/decisions/ (0001–0003) and mark them Accepted if nothing changed.

Constraints:
- No feature code. No dependencies beyond what these tasks need.
- No secrets in the repo. No google-services.json.
- Do not add Firebase to the foss flavor, ever.

Verification:
- cd android && ./gradlew spotlessCheck detekt lint testFossDebugUnitTest testPlayDebugUnitTest assembleFossDebug assemblePlayDebug  → all green
- Install app-foss-debug.apk on an emulator and confirm it opens (adb install, adb shell am start).
- cd backend/server && ./gradlew check → green
- docker compose -f infra/docker/compose.yml up -d --build && curl -fsS localhost:8080/health
- Show me the output summaries.

Hand-off:
- Update docs/phase.md: tick items, fill "Verified dependency versions" with each version and the URL you verified it at, add a log line, set Next step = "Run P-01".
- Commit: "chore: bootstrap android and backend projects with CI".
- Create the private GitHub repo jeshiomurmu/opencaller with gh repo create, push main and the phase branch, open a PR, and wait for CI to pass. Report the CI result.
```

---

## P-01 · Phase 1 — Core foundations

```
Read CLAUDE.md, docs/rules.md, docs/phase.md, docs/architecture.md §3, §6, §11, docs/design.md §2–5, and Phase 1 in docs/implementation_plan.md. First set docs/phase.md "Current phase" to 1 and copy the Phase 1 checklist into it. Work only on Phase 1. Plan first.

Goal: the shared foundations every feature will use: models, phone-number handling, encrypted database, settings, design system, test utilities, and an app shell.

Tasks:
1. core:common — AppResult<T> (Success/Failure), sealed interface AppError (Network, Auth, RateLimited(retryAfterSeconds), NotFound, Validation(message), Storage, Unexpected(cause)), DispatcherProvider interface + default impl, Clock interface, RedactingTree for Timber that masks any digit run of ≥ 7 digits to show only the last 4.
2. core:model — PhoneNumber (@JvmInline value class holding E.164), SenderId (for alphanumeric headers/short codes), CallerInfo, NameSource enum (CONTACT, SELF_REGISTERED, VERIFIED_BUSINESS, SENDER_REGISTRY, COMMUNITY_LABEL, DEVICE_INFERENCE), SpamVerdict, BlockRule (+ RuleType, RuleAction, RuleTarget), Decision, MessageCategory, OtpInfo. Pure Kotlin, KDoc on every public type.
3. core:phonenumber — PhoneNumberNormalizer using com.googlecode.libphonenumber (verify coordinates and latest version on Maven Central): normalize(raw, defaultRegion) -> AppResult<PhoneNumber>; numberType(); regionCode(); isForeign(simRegion). BucketHasher: SHA-256 of E.164 UTF-8 bytes, first N bits (default 16) as Int. Add a test vector file shared with the backend (core/testing fixtures + backend/server/src/test/resources) so both sides compute identical buckets.
   Tests: TC-PN-001…005 from docs/testing.md with fixtures for BD, IN, US, GB, NG, AE.
4. core:database — Room with KSP, all entities and DAOs from docs/architecture.md §6, exportSchema = true with schemas/ checked in. SQLCipher via net.zetetic:sqlcipher-android (verify current coordinates and the documented Room integration, SupportOpenHelperFactory). Database passphrase: random 32 bytes generated once, encrypted with an Android Keystore AES-GCM key, stored in DataStore. Add a test proving the DB file header is not the plain "SQLite format 3" string.
5. core:datastore — Proto DataStore UserSettings (blocking toggles, spam threshold enum, allowed countries, overlay enabled, announce caller mode, theme, onboarding progress). Use the protobuf Gradle plugin (verify current version).
6. core:designsystem — OpenCallerTheme with exactly the color tokens in docs/design.md §2 (light + dark), semantic colors exposed via a CompositionLocal (danger, warning, safe, business, gold), typography per §3 using Noto Sans (bundle the font files from Google Fonts / notofonts GitHub release; record licence in NOTICE.md), spacing and shape tokens per §4. Previews in light, dark and fontScale 2f.
7. core:ui — SourceChip, RiskBanner, VerifiedBadge, EmptyState (stateless, previews). CallerCard and OtpCard come in later phases.
8. core:testing — MainDispatcherRule, TestDispatcherProvider, FakeClock, fixtures folder.
9. app — Hilt Application class, MainActivity with Navigation Compose (type-safe routes) and a bottom bar with five tabs (Calls, Messages, Contacts, Protection, Me) showing placeholder EmptyState screens.

Constraints:
- No network module work yet. No telephony yet.
- Colors, fonts and sizes only from tokens.
- core:model and core:common must not depend on Android.

Verification:
- ./gradlew :core:phonenumber:test with coverage report (Kover or JaCoCo — pick one, verify plugin version) ≥ 90% lines.
- ./gradlew check and both assemble tasks green.
- Run the app: navigate all 5 tabs in light and dark.

Hand-off: update docs/phase.md (ticks, versions table, log, Next step "Run P-02"), commit "feat(core): foundations for models, numbers, db, settings, design system", push, open PR, report CI.
```

---

## P-02 · Phase 2 — Onboarding, roles, permissions

```
Read CLAUDE.md, docs/rules.md, docs/phase.md, docs/architecture.md §5, docs/design.md §7–8, Phase 2 in docs/implementation_plan.md, and test cases TC-ONB-* in docs/testing.md. Set the current phase to 2 in docs/phase.md. Plan first.

Goal: a respectful onboarding that obtains roles and permissions one at a time with explainers, and a permissions status screen.

Tasks:
1. core:telephony RoleManagerHelper: isRoleAvailable/isRoleHeld/createRequestIntent for ROLE_CALL_SCREENING, ROLE_DIALER, ROLE_SMS using android.app.role.RoleManager (API 29+). Expose a Flow<RoleState> refreshed on lifecycle resume.
2. feature:onboarding screens: Welcome → Privacy ("What leaves your phone / what never does", text from docs/prd.md §5 and architecture §10 in plain words) → Call screening role → Contacts permission → Notifications (API 33+) → Optional: default phone app → Optional: default SMS app → Overlay (optional) → Done. Max 5 visible steps by grouping optional ones on one screen.
3. Each permission/role is preceded by core:ui PermissionExplainer (add it to core:ui per docs/design.md §5).
4. Play-policy order: request READ_CALL_LOG / CALL_PHONE etc. only after ROLE_DIALER is held; request SMS permissions only after ROLE_SMS is held. Add a unit-tested PermissionPolicy that enforces this.
5. Settings → Permissions & roles status screen with Fix buttons; app-wide degraded-mode banner when call screening role is missing.
6. Persist onboarding progress in UserSettings; survive rotation and process death.

Constraints:
- No call screening logic yet (only the role).
- Never request a permission at app start without an explainer.

Verification:
- Robolectric tests for PermissionPolicy and onboarding ViewModel.
- Instrumented tests for TC-ONB-001, 002, 003, 005, 006, 007, 008 on an API 36 emulator and one API 29 emulator.
- ./gradlew check, assemble both flavors.

Hand-off: docs/phase.md updated, commit "feat(onboarding): roles and permission flow", push, PR, CI result.
```

---

## P-03 · Phase 3 — Call screening and local blocking

```
Read CLAUDE.md, docs/rules.md, docs/phase.md, docs/architecture.md §4.1–4.3 and §6, docs/design.md §5 (CallerCard, RiskBanner) and §7, Phase 3 in docs/implementation_plan.md, and TC-BLK-* in docs/testing.md. Set the current phase to 3. Plan first.

Goal: calls are screened on-device with the user's rules and a local spam list, decisions are fast and fail open, and a caller card appears.

Tasks:
1. OpenCallerScreeningService extends android.telecom.CallScreeningService, declared in the manifest with android:permission="android.permission.BIND_SCREENING_SERVICE" and an intent-filter for android.telecom.CallScreeningService (verify the exact manifest declaration on developer.android.com). It delegates to ScreeningEngine and calls respondToCall with a CallResponse built from the decision (setDisallowCall / setRejectCall / setSilenceCall / setSkipCallLog / setSkipNotification — verify each builder method's API level).
2. ScreeningEngine in core:telephony implementing the decision order in docs/architecture.md §4.1 steps 1–9 and 11 (step 10, network lookup, comes in Phase 5 — leave an interface NetworkLookup with a no-op implementation). Hard budget 1,500 ms using withTimeoutOrNull; on timeout or any exception return ALLOW with reason "timeout"/"error".
3. Contact check via ContactsContract.PhoneLookup (handle permission missing → treat as non-contact but never block when blockNonContacts is on without contacts permission; show a banner instead).
4. Rule matcher: EXACT, PREFIX, COUNTRY, REGEX. REGEX runs with a time-limited matcher (e.g. a CharSequence wrapper that checks a deadline) and the editor rejects patterns that fail a test-run budget. ALLOW beats BLOCK.
5. Toggles from UserSettings: blockHidden, blockForeign (+ allowedCountries), blockNonContacts (verified businesses still allowed — use a local isVerifiedBusiness lookup that returns false until Phase 9).
6. Local spam list lookup from the spam_list table with user threshold (HIGH = 0.85, MEDIUM = 0.6).
7. STIR/SHAKEN: on API 30+ read Call.Details.getCallerNumberVerificationStatus() and map to CallerInfo.
8. OverlayController: when the app is NOT the default dialer and overlay permission is granted, show a CallerCard window (WindowManager TYPE_APPLICATION_OVERLAY) on ring; dismiss on call end (observe call state with TelephonyCallback on API 31+, PhoneStateListener below). If overlay is not allowed, post a high-priority heads-up notification instead.
9. After-call notification with actions Block, Report (opens app), Not spam, Save contact.
10. feature:blocking: settings screen (toggles, threshold), rules editor with "Test a number" box, blocked-calls log from screening_event with Undo.
11. Debug screen (Settings → About, tap version 7×): last 100 ScreeningEvents and latency p50/p95.
12. CallerCard composable in core:ui per docs/design.md §5 with all states and previews.

Constraints:
- Never block outgoing calls. Never touch emergency numbers.
- No network calls in this phase.
- Log numbers only through RedactingTree.

Verification:
- Unit tests for ScreeningEngine covering TC-BLK-004, 005, 010, 011, 013 and every decision branch.
- Emulator script scripts/qa/blocking.sh using `adb emu gsm call` that runs TC-BLK-002, 003, 006, 008, 009, 012, 015 and prints pass/fail by reading the app's debug export (add an exported-in-debug-only content provider or adb-readable file for test results).
- ./gradlew check, assemble both flavors.
- Report p95 local decision latency from the debug screen (target < 50 ms).

Hand-off: docs/phase.md updated, commit "feat(blocking): on-device call screening engine and rules", push, PR, CI result.
```

---

## P-04a · Phase 4 — Backend: schema, auth, rate limits

```
Read CLAUDE.md, docs/rules.md, docs/phase.md, docs/architecture.md §2 (backend), §4.7, §7, §8, §10, Phase 4 in docs/implementation_plan.md, and TC-API-001…004 in docs/testing.md. Set current phase to 4. Plan first.

Goal: a production-shaped Ktor API with database, auth by phone OTP, rate limiting, errors and metrics.

Tasks:
1. Flyway migration V1__init.sql exactly as in docs/architecture.md §7. Exposed table objects mirroring it.
2. Config via environment variables (HOCON with env substitution): DB, Redis, MinIO, JWT secrets, OTP provider. Fail fast on missing config.
3. OtpProvider interface; LogOtpProvider (dev only, refuses to start when ENV=production); one real adapter behind the interface (ask me which provider before writing it; write against its official API docs only).
4. /v1/auth/start, /v1/auth/verify, /v1/auth/refresh. OTP: 6 digits, 5-minute expiry, max 5 attempts, stored hashed. JWT access 15 min; refresh 30 days, rotating, stored hashed in refresh_tokens; reuse of a rotated token revokes the device's chain.
5. Redis rate limits per docs/rules.md §7 with Retry-After.
6. StatusPages → RFC 9457 problem+json. Request IDs. Structured JSON logs (number redaction). Micrometer Prometheus /metrics on an internal port.
7. OpenAPI spec file for the endpoints so far.

Verification: ./gradlew check with Testcontainers Postgres + Redis covering TC-API-001…004; compose up; curl examples in docs/phase.md log.
Hand-off: docs/phase.md updated (Phase 4 partially ticked), commit "feat(server): schema, phone auth, rate limits", push, PR, CI result.
```

## P-04b · Phase 4 — Backend: reports, scoring, lists, lookup, search

```
Read the same docs as P-04a plus docs/architecture.md §4.6, §4.8, §9 and TC-API-005…011. Continue Phase 4. Plan first.

Tasks:
1. POST /v1/reports (batch ≤ 50), validation with libphonenumber server-side, reporter trust default 0.5.
2. Scoring job (coroutine scheduler, every 15 min, leader-locked with a Postgres advisory lock): Bayesian formula from architecture §9 with configurable priors and half-life; verified business numbers capped below the spam threshold and a dispute-queue item created instead (TC-API-006). Unit tests with hand-computed fixtures (TC-API-005).
3. List builder (nightly + on demand): per country, full list and delta since previous version, compact binary format documented in docs/architecture.md (append a §13 "List file format" describing header, version, sorted entries, sha256), uploaded to MinIO, row in list_versions. GET /v1/lists/{countryIso}/manifest.
4. GET /v1/lookup/buckets/{cc}/{bucketId}: returns entries with full SHA-256 hash + info; respects visibility (TC-API-008); ETag; Cache-Control. Use the same test vectors as the Android BucketHasher.
5. GET /v1/numbers/{e164}: explicit search with daily quota (TC-API-009); records a profile_views row (kind=search) unless the searcher is incognito.
6. Disputes and unlist endpoints; unlist web form served at /unlist (server-rendered HTML, works without the app) (TC-API-010).
7. GET /v1/sender-ids/{countryIso}?since= and GET /v1/otp-sources?since= backed by seed CSVs in backend/server/seed/ (empty structure + a few clearly-fake example rows; real data arrives in Phase 9).
8. scripts/smoke.sh that runs the whole flow against compose with curl + jq.
9. backend/load/bucket.js k6 script; seed 1M synthetic numbers; record results in docs/testing.md TC-API-011.

Verification: ./gradlew check; smoke.sh passes; k6 result recorded.
Hand-off: Phase 4 fully ticked, commit "feat(server): reports, scoring, lists, private lookup, search", push, PR, CI result.
```

---

## P-05 · Phase 5 — Sync, live lookup, reporting, search (v0.1 alpha)

```
Read CLAUDE.md, docs/rules.md, docs/phase.md, docs/architecture.md §4.1 step 10, §4.6–4.8, §10, Phase 5 in docs/implementation_plan.md, TC-SYNC-*, TC-LOOK-*, TC-REP-*, TC-E2E-001. Set current phase to 5. Plan first.

Tasks:
1. core:network: Retrofit + OkHttp + kotlinx.serialization converter (verify the official converter artifact), AuthInterceptor, Authenticator for refresh, base URL from BuildConfig (debug: 10.0.2.2:8080; release: from Gradle property), certificate pinning only in play release builds with a backup pin.
2. Sign-up flow (optional) in onboarding and Me tab: number entry → OTP → done. Tokens encrypted (Keystore AES-GCM) in DataStore.
3. SpamListSyncWorker (periodic daily; unmetered network by default; user can allow mobile data): manifest → download delta or full → verify sha256 → apply in a single Room transaction → keep old list on failure (TC-SYNC-001, 002).
4. NetworkLookup implementation: BucketHasher → cached bucket (Room lookup_bucket + number_info, 24 h) or GET bucket with 800 ms timeout → match full hash locally. Wire into ScreeningEngine step 10 (TC-LOOK-001).
5. Privacy test TC-LOOK-002: MockWebServer asserts that no request path, query or body contains the E.164 string or its national form during a screened call.
6. Reporting: report sheet from after-call notification/card and blocked log (spam/not spam + category list from server or a local default); report_outbox + ReportUploadWorker (TC-REP-001).
7. feature:search: number search with quota indicator and error states from docs/design.md §8.
8. Caller card shows SourceChip and report count. "Why am I seeing this?" sheet.
9. Links to dispute and unlist flows.

Verification: unit + Robolectric tests; TC-E2E-001 with two emulators and local compose (scripts/e2e/report_to_block.sh); ./gradlew check.

Release (alpha):
- Bump versionName 0.1.0-alpha, versionCode accordingly. Update CHANGELOG.md.
- Build a signed foss release APK locally using a keystore OUTSIDE the repo (ask me for the path; never commit it).
- Create a GitHub pre-release v0.1.0-alpha with gh release create, attach the APK and its SHA-256.

Hand-off: docs/phase.md updated, commit "feat(lookup): list sync, private live lookup, reporting, search", push, PR, CI, then tag after merge.
```

---

## P-06a · Phase 6 — In-call service and in-call UI

```
Read CLAUDE.md, docs/rules.md, docs/phase.md, docs/architecture.md §4.2–4.3 and §5, docs/design.md, Phase 6, TC-INCALL-*. Set current phase to 6. Plan first.

Tasks:
1. Default dialer requirements: verify on developer.android.com what an app must declare to be eligible for ROLE_DIALER (an activity handling ACTION_DIAL with and without tel: data, and an InCallService with BIND_INCALL_SERVICE permission and the android.telecom.IN_CALL_SERVICE_UI meta-data). Implement exactly that.
2. OpenCallerInCallService: track calls (Call.Callback), expose a CallStateRepository Flow consumed by UI; handle ringing via a full-screen intent notification (CallStyle notification) so lock-screen answering works.
3. InCallActivity (Compose): answer/decline (+ decline with quick SMS reply), mute, speaker, Bluetooth audio routing via CallAudioState / CallEndpoint APIs (verify which applies to InCallService on API 34+), keypad with DTMF, hold, add call, merge, swap, end. CallerCard at top.
4. After-call screen as an activity when we are the default dialer.
5. Keep screening-only mode working when not default dialer.
Verification: TC-INCALL-001 on emulator; TC-INCALL-002, 003 on device; ./gradlew check.
Hand-off: docs/phase.md, commit "feat(incall): in-call service and UI", push, PR.
```

## P-06b · Phase 6 — Dialer, call log, contacts

```
Continue Phase 6. Read TC-DIAL-*, TC-LOG-*.
Tasks:
1. feature:dialer: keypad (Noto Sans Mono), T9 matching over contacts and call log (pure-Kotlin matcher, unit-tested TC-DIAL-002), paste/format as you type (libphonenumber AsYouTypeFormatter), dual-SIM picker using PhoneAccountHandle list, voicemail long-press on 1, placeCall via TelecomManager, MMI/USSD passthrough.
2. feature:calllog: CallLog.Calls provider (only after ROLE_DIALER), grouped by number/day, filters missed/blocked/spam (join with screening_event), delete, search.
3. feature:contacts: list with fast scroller, detail (caller info + actions), create/edit via ContactsProvider batch operations, favourites. If you reuse ideas or code from Fossify Phone (GPL-3.0), keep their copyright headers and add them to NOTICE.md.
4. Quick-reply templates editable in Settings.
5. Emergency numbers: verify they are never intercepted or delayed (TC-BLK-013).
Verification: tests listed above; ./gradlew check; manual device run of TC-DIAL-003, 004.
Hand-off: Phase 6 ticked, commit "feat(dialer): dialer, call log, contacts", push, PR.
```

---

## P-07a · Phase 7 — Default SMS app

```
Read CLAUDE.md, docs/rules.md, docs/phase.md, docs/architecture.md §4.4, §5, §6, Phase 7, TC-SMS-001…003. Set current phase to 7. Plan first.
Tasks:
1. Verify on developer.android.com the components a default SMS app must declare: a BroadcastReceiver for SMS_DELIVER_ACTION (permission BROADCAST_SMS), a receiver for WAP_PUSH_DELIVER_ACTION with MIME application/vnd.wap.mms-message (permission BROADCAST_WAP_PUSH), a service for ACTION_RESPOND_VIA_MESSAGE (permission SEND_RESPOND_VIA_MESSAGE), and an activity for ACTION_SENDTO with sms/smsto/mms/mmsto schemes. Implement exactly these.
2. Store incoming SMS in Telephony.Sms.Inbox (we are the default app, so we must), with goAsync() and a ≤ 10 s pipeline.
3. Conversations list, thread view, compose; multipart SMS; delivery/sent reports; dual-SIM send via SubscriptionManager; MMS receive/send with images via SmsManager download/send APIs; group MMS.
4. Notifications per conversation (MessagingStyle), reply from notification, mark read.
5. Never duplicate SMS bodies into our Room DB; only MessageMeta.
Verification: TC-SMS-001, 002 emulator (adb emu sms send), TC-SMS-003 device; ./gradlew check.
Hand-off: docs/phase.md, commit "feat(messages): default SMS app", push, PR.
```

## P-07b · Phase 7 — Sender ID, OTP, safety guard, classifier

```
Continue Phase 7. Read TC-SMS-004, 005 and TC-OTP-*. Read the research section "Who is calling or texting, including OTP senders" summarised in docs/prd.md F-ID-08 and architecture §4.4–4.5.
Tasks:
1. ml/sms-classifier: Python project (pyproject.toml) that trains a small multilingual text classifier on core:testing sms_corpus train/val splits and exports to .tflite for LiteRT with int8 quantisation; document dataset sources and licences in ml/sms-classifier/README.md. If the corpus is too small, ship a rules-only classifier first and record the gap in docs/phase.md Open issues — do not pretend a model exists.
2. core:ml: SmsClassifier (LiteRT interpreter; verify the current LiteRT Android artifact and API), OtpExtractor (codes 4–8 digits, keyword dictionaries for en/bn/hi, expiry phrases, domain-bound "@domain #code" last line per the origin-bound one-time codes format, 11-char app hash suffix), TransactionParser (amount/currency/merchant/last-4 for top banks in BD/IN/US, per-bank templates in assets), LinkChecker (lookalike detection with Unicode confusables + hash-prefix check against a server list).
3. SenderResolver order per architecture §4.4, including Indian header parsing "<2-letter operator+circle>-<HEADER>-<S|T|P|G>" mapping suffix to category, sender_id table, otp_app_hash, verification_range.
4. Smart inbox tabs (Personal, OTP, Transactions, Bills, Deliveries, Promotions, Spam) and inbox cleaner.
5. OtpCard + OTP notification with VISIBILITY_PRIVATE and a public version that hides the code; auto-delete option.
6. OtpSafetyGuard: listen to call state (CallStateRepository when default dialer; TelephonyCallback otherwise); if an OTP SMS or a flash call (missed call ≤ 3 s from a verification range) arrives during or ≤ 2 min after a call with a non-contact → full-screen OtpGuardScreen via full-screen intent (verify USE_FULL_SCREEN_INTENT rules for API 34+).
7. OTP-burst / unrequested alert (TC-OTP-009).
8. Scheduled SMS with WorkManager.
Verification: TC-OTP-001…009, TC-SMS-004, 005; extraction precision/recall and classifier macro-F1 printed by a Gradle test task and recorded in docs/testing.md; ./gradlew check.
Hand-off: Phase 7 ticked, commit "feat(otp): sender identification, OTP guard, SMS classification", push, PR.
```

---

## P-08 · Phase 8 — Profiles and social

```
Read CLAUDE.md, docs/rules.md, docs/phase.md, docs/prd.md §6.4, docs/architecture.md §7–8, Phase 8, TC-SOC-*. Set current phase to 8. Plan first.
Tasks (app + server together):
1. Server: GET/PUT /v1/me (display name, avatar upload via pre-signed MinIO URL, visibility everyone/registered_only/nobody, incognito), DELETE /v1/me with immediate de-listing and a purge job ≤ 30 days; bucket and search responses honour visibility.
2. Server: profile_views recording rules (search + profile view; skip when viewer incognito), GET /v1/me/viewers and /v1/me/searchers (supporter-gated via entitlement check stub until Phase 10), 90-day retention job.
3. Server: contact requests endpoints.
4. App feature:profile: my profile editor, visibility explainer, viewers/searchers lists, incognito toggle, contact requests inbox, delete account (double confirmation).
5. Badge rendering on CallerCard for supporter/gold (data from bucket/search info).
6. ADR 0004: viewer/searcher logging — what is stored, retention, who can see it.
Verification: TC-SOC-001…003; server and app tests; ./gradlew check both sides.
Hand-off: docs/phase.md, commit "feat(profile): self-registration, visibility, viewers, contact requests", push, PR.
```

---

## P-09 · Phase 9 — Business and sender registries (v0.5 beta)

```
Read CLAUDE.md, docs/rules.md, docs/phase.md, docs/prd.md §6.6, ADR 0001, Phase 9, TC-BIZ-*. Set current phase to 9. Plan first.
Tasks:
1. Server: business claim API (OTP or flash call to the claimed number proves control; document upload to MinIO with size/type limits; status pending), admin review web UI (server-rendered, separate port, basic auth + IP allowlist from env, audit log table added via V2 migration), approval publishes business data into buckets and lists.
2. Server: sender-ID importer framework (interface Importer { fetch(); map(); upsert() }), CSV importer for manually obtained registry extracts, and a scheduled job; only real registry integrations where we have documented access — otherwise leave the CSV path and record the gap.
3. Server: curation tool (admin page) for otp app-hash table and verification-provider number ranges.
4. App: feature:business claim flow, business profile view, verified badge on caller card and SMS sender, SourceChip everywhere (F-ID-09); isVerifiedBusiness now real for blockNonContacts.
Verification: TC-BIZ-001, 002; ./gradlew check.
Beta release:
- versionName 0.5.0-beta; CHANGELOG.
- GitHub pre-release with signed foss and play APKs + checksums.
- Prepare F-Droid metadata (fastlane/metadata/android/en-US/ title, short and full description, screenshots, changelogs) and open a request at gitlab.com/fdroid/rfp (I will submit it; you prepare the text).
- Play Console closed testing: produce the AAB and the text for the Data safety form and the SMS/Call Log permissions declaration based on docs/architecture.md §5 and §10. I will submit.
Hand-off: docs/phase.md, commit, tag v0.5.0-beta after merge.
```

---

## P-10 · Phase 10 — Supporter tier and utilities

```
Read CLAUDE.md, docs/rules.md, docs/phase.md, docs/prd.md §6.5, Phase 10, TC-UT-*, TC-BILL-*. Set current phase to 10. Plan first.
Tasks:
1. EntitlementProvider interface in core:data. play flavor: Google Play Billing Library (verify latest version and its current API; use subscriptions with base plans: supporter, gold, family) + POST /v1/billing/verify on server using the Google Play Developer API (purchases.subscriptionsv2) with a service account from env. foss flavor: entitlements unlocked when the server reports self-hosted mode, plus a donation link.
2. Extended spam list download when entitled (F-PR-03).
3. Ghost Call: schedule with WorkManager/AlarmManager; show a fake incoming-call screen through a full-screen intent notification; never place or register a real call.
4. Announce Caller: TextToSpeech in the user's language; modes always / headphones only / never; respects Do Not Disturb.
5. Backup/restore: BackupDestination interface; play = Google Drive appDataFolder via Drive REST API with Google Sign-In/Credential Manager (verify the current recommended auth flow); foss = encrypted archive via Storage Access Framework (AES-GCM with a user passphrase, Argon2 or PBKDF2 — verify available library). Contents: settings, rules, message_meta, screening_event, recordings optional.
6. Themes incl. Gold.
7. NotificationListenerService for WhatsApp, Telegram, Signal incoming-call notifications: extract a phone number only if it is present in the notification text; otherwise show nothing. Document per-app tested versions.
Verification: TC-UT-001…004, TC-BILL-001 (license testers); ./gradlew check.
Hand-off: docs/phase.md, commit "feat(premium): supporter tier, ghost call, announce caller, backup", push, PR.
```

---

## P-11 · Phase 11 — VoIP, Flash, video caller ID

```
Read CLAUDE.md, docs/rules.md, docs/phase.md, docs/architecture.md §4.9, Phase 11, TC-VOIP-*. Set current phase to 11. Plan first.
Tasks:
1. infra: add LiveKit server (and its TURN config) to compose using LiveKit's official self-hosting docs; document ports and TLS.
2. Server: POST /v1/voip/token (LiveKit access token with room = sorted pair of user IDs + nonce), call-invite push via PushProvider (FCM in play, UnifiedPush in foss; payload contains only an opaque invite ID).
3. App feature:voip: LiveKit Android SDK for media; androidx.core:core-telecom (Jetpack Telecom) CallsManager to register the VoIP call so it behaves like a system call (verify current API); incoming VoIP call UI reuses InCallActivity components.
4. Flash messages: short predefined pings over the same push channel, stored locally only.
5. Video caller ID: record ≤ 10 s with CameraX, upload via pre-signed URL; server transcodes (ffmpeg in a worker container) to a small MP4; callee downloads only over Wi-Fi by default and plays muted in the in-call UI.
Verification: TC-VOIP-001 on two devices across networks; ./gradlew check.
Hand-off: docs/phase.md, commit "feat(voip): app-to-app calls, flash messages, video caller id", push, PR.
```

---

## P-12 · Phase 12 — Family protection, scam feed, business dashboard (v1.0)

```
Read CLAUDE.md, docs/rules.md, docs/phase.md, docs/prd.md F-PR-14, F-PR-15, Phase 12, TC-FAM-*. Set current phase to 12. Plan first.
Tasks:
1. Server: family groups (max 5), invites, explicit consent recorded with timestamp; alerts endpoint; only counts and alert types are shared, never numbers or message text.
2. App: feature:family — create/join, consent screen (plain words on what the admin will see), alert settings; OtpSafetyGuard posts a family alert when the member opted in.
3. Scam feed: admin-curated cards per country on the server; app shows them in Protection tab.
4. Business dashboard (F-BZ-04): server-rendered web pages for verified businesses (login by OTP to a claimed number): edit profile and call reasons, see daily counts of data the server already holds: explicit searches of their numbers, spam / not-spam reports and dispute status. Never add client telemetry about calls answered or declined; that would break the privacy invariants in docs/architecture.md §10.
Verification: TC-FAM-001, 002; full manual QA checklist in docs/testing.md §5.
Release v1.0.0: versionName 1.0.0, CHANGELOG, GitHub release with signed APKs/AAB, Play production staged rollout text (10% → 50% → 100%), F-Droid update. I submit store changes.
Hand-off: docs/phase.md, commit, tag v1.0.0 after merge.
```

---

## P-13a · Phase 13 — Telephony lines infrastructure and recording

```
Read CLAUDE.md, docs/rules.md, docs/phase.md, docs/prd.md F-UT-03, docs/architecture.md §4.9, Phase 13, TC-REC-*. Set current phase to 13. Plan first. Ask me which SIP trunk provider and countries before writing provider-specific config.
Tasks:
1. infra/deploy/telephony.md: provider-agnostic guide for LiveKit SIP inbound trunks and dispatch rules (follow LiveKit SIP official docs only).
2. Server: POST /v1/lines/recording allocates the user's recording number/room; per-country legal switch (env/admin) that disables recording where not approved.
3. backend/agents: Python LiveKit agent "recorder": plays a beep/announcement at join, records via LiveKit Egress to MinIO, notifies the app, deletes the object ≤ 1 h after download confirmation or after 24 h max.
4. App feature:recording: Record button during calls (dial recording line + merge using the InCallService conference APIs), consent reminder, recordings list, playback, optional transcript + summary (server-side STT/LLM only if the user enables it).
Verification: TC-REC-001 on a real SIM; agent tests with pytest; ./gradlew check.
Hand-off: docs/phase.md, commit "feat(recording): recording line", push, PR.
```

## P-13b · Phase 13 — AI call-screening assistant

```
Continue Phase 13. Read docs/prd.md F-UT-04, TC-AST-*.
Tasks:
1. backend/agents "assistant": LiveKit Agents voice pipeline (STT → LLM → TTS) with providers configurable by env; system prompt: greet, say the user is unavailable, ask name and reason, never collect OTPs/passwords/bank details, end politely; stream transcript + extracted {name, reason, urgency, likely_spam} to the app over the LiveKit data channel.
2. Server: POST /v1/lines/assistant allocates the user's assistant number.
3. App feature:assistant: setup wizard that explains conditional call forwarding and offers to dial the standard GSM MMI codes (**61* no reply, **62* unreachable, **67* busy, ##002# to cancel all) with the assistant number, plus "my carrier doesn't support this" guidance; live transcript screen with actions: pick up (call back), send quick reply via the agent, mark spam and hang up; history of screened calls.
4. Cost meter: minutes used this month (from server).
Verification: TC-AST-001 on a real SIM; agent unit tests with mocked providers; ./gradlew check.
Hand-off: docs/phase.md, commit "feat(assistant): AI call screening via call forwarding", push, PR.
```

## P-13c · Phase 13 — Voice-clone scanner

```
Continue Phase 13. Read docs/prd.md F-PR-13, TC-SCAN-001.
Tasks:
1. ml/voice-spoof: FastAPI service wrapping an AASIST-family anti-spoofing model. Check the model's licence before using its weights; if redistribution isn't allowed, document how a self-hoster downloads them. Endpoint takes 4 s of 16 kHz mono audio and returns a probability. Include an evaluation script and record its equal error rate on the eval set you use, with telephony-codec degradation, in ml/voice-spoof/README.md.
2. backend/agents "scanner": joins the merged call, samples audio, calls the service, returns result.
3. App feature:scanner: "Scan voice" action in the in-call UI, merge flow, result as "Likely human / Possibly AI-generated" with an explanation that the check can be wrong, plus transcript-based scam cues.
Verification: TC-SCAN-001; ./gradlew check; pytest.
Hand-off: Phase 13 ticked, commit "feat(scanner): voice-clone warning", push, PR. Tag v1.1.0 after merge.
```

---

## P-14 · Phase 14 — Hardening

```
Read CLAUDE.md, docs/rules.md, docs/phase.md, docs/design.md §11, docs/testing.md §5, Phase 14. Set current phase to 14. Plan first.
Tasks:
1. Accessibility: run the checklist in docs/design.md §11 on every screen; fix all findings; add Compose semantics tests for CallerCard, OtpGuardScreen and the blocked log.
2. Performance: add a :benchmark module with Macrobenchmark for cold start and a screening micro-benchmark; generate Baseline Profiles; record results in docs/testing.md.
3. Battery: 24 h test procedure and results in docs/testing.md.
4. Security: walk the OWASP MASVS checklist, record pass/fail per control in docs/security.md; verify pinning, token storage, exported components (no unintended exported=true), Intent validation, SQL injection safety on server, admin port isolation.
5. Privacy: compare actual network traffic during the manual QA run with architecture §10; write PRIVACY.md (user-facing privacy policy draft) and TERMS.md drafts for my legal review.
6. Localization: extract all strings; add bn, hi; RTL check with ar pseudo-locale.
7. Close or downgrade every open S1/S2 bug in docs/testing.md.
Verification: all P0/P1 test cases pass; ./gradlew check.
Hand-off: docs/phase.md, commit "chore: accessibility, performance, security and privacy hardening", push, PR.
```

---

## P-15 · Phase 15 — Release automation and deployment to GitHub

```
Read CLAUDE.md, docs/rules.md, docs/phase.md, Phase 15 in docs/implementation_plan.md. Set current phase to 15. Plan first.

Goal: pushing a tag vX.Y.Z builds, signs and publishes everything automatically, and the backend can be deployed to a fresh VPS from the guide.

Tasks:
1. Android signing from CI: release signingConfig reads from environment variables (KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD) written to a temp file in the workflow; nothing committed. Document how I create the keystore with keytool and add the four GitHub Actions secrets with `gh secret set`.
2. .github/workflows/release.yml on push of tags v*: run full check, build assembleFossRelease, assemblePlayRelease, bundlePlayRelease, generate SHA-256 checksums, create a GitHub Release with notes taken from CHANGELOG.md for that version, and upload the artifacts. Verify current major versions of every action used.
3. Backend images: workflow that builds and pushes backend/server and backend/agents images to ghcr.io/jeshiomurmu/opencaller-* on tags, with provenance/SBOM if the build action supports it.
4. infra/deploy/README.md: step-by-step VPS guide (Ubuntu LTS): Docker install from Docker's official docs, compose.prod.yml, Caddy for TLS, environment file, Flyway on start, nightly pg_dump to off-site storage with restore test, Prometheus + Grafana, uptime check. Include a "fresh server in < 1 hour" checklist.
5. F-Droid: fastlane metadata complete; note reproducible-build steps and that the foss flavor contains no proprietary dependencies (verify with ./gradlew :app:dependencies --configuration fossReleaseRuntimeClasspath and record the output summary).
6. Play: release checklist (target API 36 confirmed, Data safety, permissions declaration, content rating, privacy policy URL, staged rollout plan).
7. README badges (CI status, latest release, licence), CONTRIBUTING.md, CODE_OF_CONDUCT.md (Contributor Covenant — fetch the official text, don't type from memory), SECURITY.md (how to report vulnerabilities).
8. Make the GitHub repo public only when I confirm.

Verification:
- Push a test tag v0.0.0-ci-test on a branch; confirm the release workflow produces signed artifacts; then delete that release and tag.
- Bring up the backend on a throwaway VPS (or local VM) by following infra/deploy/README.md exactly; report time taken and any doc fixes.

Hand-off: Phase 15 ticked, docs/phase.md marks the project "In maintenance", commit "ci: release automation and deployment guide", push, PR.
```

---

## Utility prompts

### U-1 · Resume after a break or a fresh session

```
Read CLAUDE.md, docs/rules.md and docs/phase.md. Summarise: current phase, what's done, what's next, open issues, and the last verification result. Then run the verification commands in docs/rules.md §6 and tell me whether the tree is green before doing anything else.
```

### U-2 · Fix a failing build or test

```
The build/test below is failing. Read docs/rules.md §5. Reproduce the failure first and show the exact error. Find the root cause (not a workaround). Fix it, add or adjust a test that would have caught it, run the full verification commands, and log it in docs/testing.md Bug log if it took more than one attempt. Do not weaken or delete tests to make them pass.
<paste error output>
```

### U-3 · Phase exit review

```
Act as a strict reviewer. Read docs/implementation_plan.md for phase <N>, docs/testing.md test cases for that phase, and the git diff since the phase branch started. For each checklist item and exit criterion: state Pass/Fail with evidence (test name, command output, file path). List privacy-invariant risks (architecture §10), rule violations (docs/rules.md §2), and any unverified dependency versions. Do not fix anything; produce the report only.
```

### U-4 · Bug report to fix

```
Read docs/testing.md Bug log entry <BUG-ID> and the linked test case. Write a failing test that reproduces it, fix it, confirm the test passes and nothing else regressed, update the bug row (status fixed, commit hash), and commit "fix(<module>): <summary> (<BUG-ID>)".
```

### U-5 · Dependency update

```
For each dependency in android/gradle/libs.versions.toml and backend/server/gradle/libs.versions.toml, look up the latest stable version on its official source. Propose updates in a table (current, latest, source URL, breaking changes from release notes). Apply only non-major updates unless I approve majors. Run full verification and update the versions table in docs/phase.md.
```
