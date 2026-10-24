# Testing, QA and bug log — OpenCaller

Agents: reference test case IDs (TC-…) in commits and PRs. Add a test case here **before** implementing a feature, then make it pass.

## 1. Test pyramid

| Layer | Tool | Where | Runs in CI |
| --- | --- | --- | --- |
| Unit (pure Kotlin) | JUnit4, MockK, Turbine, kotlinx-coroutines-test | `src/test` in each module | Every push |
| Android unit (framework classes) | Robolectric | `src/test` | Every push |
| Room / migrations | Room testing `MigrationTestHelper` | `core:database` `src/androidTest` | Emulator job |
| Compose UI | Compose UI test | `src/androidTest` | Emulator job |
| Screenshot (optional) | Roborazzi or Paparazzi (pick one in an ADR) | `src/test` | Every push |
| Instrumented telephony | Emulator console (`adb emu …`) scripted in `scripts/qa/` | manual + nightly | Nightly |
| Performance | Macrobenchmark, Baseline Profiles | `:benchmark` module | Weekly |
| Server unit/integration | Ktor `testApplication`, Testcontainers | `backend/server/src/test` | Every push |
| Load | k6 (script in `backend/load/`) | local | Before release |
| End-to-end | Two emulators + local compose | `scripts/e2e/` | Before release |

Coverage targets: `core:*` ≥ 80% lines (≥ 90% for `core:phonenumber`, `core:telephony` engine, `core:ml` extractors); server ≥ 80%.

## 2. Emulator telephony toolkit

Android Emulator console commands (via `adb emu`):

```bash
adb emu gsm call +15551230001        # incoming call
adb emu gsm accept +15551230001      # caller side accepts (for outgoing tests)
adb emu gsm cancel +15551230001      # end call
adb emu sms send +15551230001 "Your code is 482913"
```

Limits: the emulator cannot simulate hidden numbers, STIR/SHAKEN verdicts, dual SIM, flash calls from real provider ranges, or carrier call forwarding. Those cases are marked **[device]** and run on a physical phone with a SIM.

## 3. Test fixtures

`core:testing/src/main/resources/fixtures/`:

- `numbers.csv` — sample numbers per country (BD, IN, US, GB, NG, AE) with expected E.164, type, region
- `sms_corpus/` — anonymised SMS samples labelled with category, otp code, brand, origin; split `train/val/test` (the `test` split is never used for training)
- `spam_list_small.csv` — synthetic seed spam list for tests (`e164,score,category,reportCount`)
- `sender_ids.csv` — sample registry rows

Never use real people's numbers. Use reserved/fictional ranges where possible (e.g. US 555-01XX) and synthetic numbers elsewhere.

## 4. Test cases

Format: ID · Title · Steps · Expected · Type (U = unit, R = Robolectric, I = instrumented/emulator, D = physical device, M = manual).

### Onboarding (Phase 2)

| ID | Title | Steps | Expected | Type |
| --- | --- | --- | --- | --- |
| TC-ONB-001 | Grant call screening role | Fresh install → onboarding → Allow | `RoleManager.isRoleHeld(ROLE_CALL_SCREENING)` true; next step shown | I |
| TC-ONB-002 | Deny call screening role | Deny in system dialog | Degraded banner on home; no crash; Settings shows "Fix" | I |
| TC-ONB-003 | Dialer role optional | Skip dialer role | App works in screening-only mode; Calls tab shows "Set as default phone app" card | I |
| TC-ONB-004 | No permission before explainer | Instrument all `requestPermissions` calls | Every request preceded by explainer screen | R |
| TC-ONB-005 | Role revoked later | Revoke role in system settings, return to app | Banner appears on resume | I |
| TC-ONB-006 | Notification permission API 33+ | Deny | After-call card falls back to in-app history; no crash | I |
| TC-ONB-007 | Overlay denied | Deny `SYSTEM_ALERT_WINDOW` | Caller ID shown as heads-up notification instead | I |
| TC-ONB-008 | Rotation & process death | Rotate / kill during onboarding | Resumes at same step | I |

Where they run (Phase 2):

- **TC-ONB-002, 003, 006, 007, 008:** `app/src/androidTest/.../onboarding/OnboardingTest.kt`, CI `emulator` job on API 29 and API 36 (test orchestrator, app data cleared before each test). TC-ONB-006 needs Android 13+ and is skipped on API 29.
- **TC-ONB-001, 003 (with the role held), 005 and 008 (real process death):** `scripts/ci/emulator-smoke.sh`, on the same emulators, driven with adb from outside the app. Granting or revoking the call screening role can make Android kill the app process (seen on API 36), which in an instrumented test is also the test process; the script survives that and checks that onboarding resumes. It grants the role in the system dialog, finishes onboarding (screening-only mode, Calls tab card, no banner), revokes the role with `cmd role` and checks the banner on return, and kills the process at step 3 to check that onboarding resumes there.
- **TC-ONB-004:** `feature/onboarding/.../OnboardingViewModelTest.everyRequestComesFromAnExplainerContinue` (Robolectric). The settings screen follows the same rule (`PermissionsStatusViewModelTest.fixShowsTheExplainerBeforeAnyRequest`).
- **Unit-level coverage of the same cases:** `OnboardingViewModelTest`, `PermissionsStatusViewModelTest`, `MainViewModelTest`, `AccessStatusTest`, `PermissionPolicyTest`, `RoleManagerHelperTest`.
- **TC-ONB-006 and 007 until the after-call card (Phase 3) and caller ID card (Phase 3–6) exist:** the tests check that the fallback is chosen and shown in Settings → Permissions & roles ("Call summaries stay in your call history in the app", "Caller ID appears as a notification"), and that nothing crashes.

### Phone numbers (Phase 1)

| ID | Title | Expected | Type |
| --- | --- | --- | --- |
| TC-PN-001 | Normalize national BD number `01712345678` with region BD | `+8801712345678` | U |
| TC-PN-002 | Normalize IN number with spaces/dashes | Valid E.164 | U |
| TC-PN-003 | Short codes / alphanumeric senders | Not parsed as E.164; returned as `SenderId` | U |
| TC-PN-004 | Invalid input | `AppError.Validation`, no crash | U |
| TC-PN-005 | Bucket hasher stability | Same input → same 16-bit bucket; matches server implementation test vector | U |

### Blocking and screening (Phase 3)

| ID | Title | Steps | Expected | Type |
| --- | --- | --- | --- | --- |
| TC-BLK-001 | Contact always rings | Save contact; contact on block list prefix | Contact rings (allow beats block for contacts) | I |
| TC-BLK-002 | Exact block | Add exact rule; `adb emu gsm call` | Call rejected; event logged reason=rule | I |
| TC-BLK-003 | Prefix block | Rule `+1555123*` | Matching numbers rejected, others ring | I |
| TC-BLK-004 | Regex block with timeout | Catastrophic regex `(a+)+$` | Rule editor rejects or evaluation times out safely; call rings | U/R |
| TC-BLK-005 | Allow rule beats block | Allow exact + block prefix covering it | Rings | U |
| TC-BLK-006 | Block foreign | SIM country BD, call from +1 | Rejected; with US in allow list → rings | I |
| TC-BLK-007 | Block hidden | Hidden-number call | Rejected | D |
| TC-BLK-008 | Block non-contacts | Unknown number | Rejected; verified business allowed | I |
| TC-BLK-009 | Spam list threshold | Number in list score 0.9, threshold high (0.85) | Rejected; score 0.7 → rings with Suspicious card | I |
| TC-BLK-010 | Fail open | Inject exception in engine | Call rings; event reason=error | R |
| TC-BLK-011 | Budget exceeded | Fake network delay 3 s | Decision returns allow ≤ 1,500 ms | U |
| TC-BLK-012 | Outgoing never blocked | Dial a blocked number | Call proceeds | I |
| TC-BLK-013 | Emergency | Dial local emergency number in test mode (do not place real call; verify routing code path with fake) | Never intercepted | U — `ScreeningEngineTest` (never intercepted, incoming or outgoing; decided before any settings, contacts, list or network lookup, with no time passing even when every lookup is slow), `DialPadViewModelTest` (the dial pad places it at once through `placeCall`: no call-permission explainer, SIM picker or hand-off in the way). Device check in Phase 6 exit QA: dial pad shows the number, Call hands it to the platform (do not complete a real call) |
| TC-BLK-014 | STIR verdict | Verified/failed call on supporting carrier | Card shows "Verified by carrier" / "Possibly spoofed" | D |
| TC-BLK-015 | Undo block | Tap Undo in snackbar / log | Rule removed, next call rings | I |

Where they run (Phase 3):

- **Unit (JVM), `core/telephony/src/test/.../screening/`:** `ScreeningEngineTest` covers every decision branch of `architecture.md` §4.1 and TC-BLK-001…006, 008, 009, 010 (an injected failure ends in ALLOW, reason `error`), 011 (a 3 s network lookup is abandoned at 1,500 ms of virtual time; a second test proves a *blocking* lookup on real threads can't hold the call either), 012, 013 (emergency numbers are allowed before settings or rules are even read) and 014 at the mapping level. `RuleMatcherTest` / `RuleValidatorTest` cover TC-BLK-004 (catastrophic patterns hit the regex watchdog; the editor rejects them), `ScreeningCoordinatorTest` the answer → log → caller ID sequence and that failures after the answer never affect the call.
- **Robolectric:** `DeviceScreeningTest` (CallResponse per decision, `Call.Details` mapping incl. STIR, contacts without permission, home region), `RepositoryTest` (`core:data`), `CallerIdTest` (heads-up and after-call notifications, at most three actions, nothing without the notification permission, after-call summary only after the call ends and never for contacts), `BlockingViewModelsTest` (rules editor incl. Undo = TC-BLK-015, Test a number with the real engine, Unblock from the log, settings, reports).
- **Emulator, `scripts/qa/blocking.sh`** (CI `emulator` job on API 29 and 36, after the onboarding smoke script): TC-BLK-001 (a contact written through the contacts provider rings although a block prefix covers it), 002, 003, 004 (on the device's ICU regex engine), 006, 008, 009, 012 and 015, with `adb emu gsm call`. The debug build's QA receiver sets rules and settings and overrides what the emulator can't fake (SIM country, verified business); every decision is read back from the debug export (`files/qa/screening_events.tsv`, `caller_id.tsv` via `run-as`). Latency gates, over 20 sample calls: the app's own time per decision (the engine's step timings minus the wait for Android's contacts lookup) must have p95 < 50 ms, and the total on the debug screen p95 < 100 ms. The contacts provider alone swings 20–90 ms on the shared CI emulators (the same commit passed and failed the old 50 ms total gate, BUG-0002), so the exit criterion's 50 ms total is checked on a phone (below).
- **Device only:** TC-BLK-007 (hidden numbers; see §7) and TC-BLK-014 (STIR needs a carrier that signs calls).
- **Latency on a phone (exit criterion, manual):** install the debug build, give it the call screening role and the contacts permission, take 20+ calls (contacts, blocked and unknown numbers), then open Me → Settings → About (tap the version 7×) and read "Local decisions: … p95". It must be under 50 ms; record the phone model, Android version and the numbers in `docs/phase.md`.

### Backend (Phase 4)

| ID | Title | Expected | Type |
| --- | --- | --- | --- |
| TC-API-001 | OTP rate limit | 4th `/auth/start` in 1 h for same number → 429 with `Retry-After` | U |
| TC-API-002 | Verify wrong code 5 times | Code invalidated | U |
| TC-API-003 | Refresh rotation | Old refresh token rejected after rotation | U |
| TC-API-004 | Report validation | Invalid E.164 → 400 problem+json | U |
| TC-API-005 | Scoring math | Fixture reports → expected posterior ±1e-6 | U |
| TC-API-006 | Verified business never auto-spam | Many spam reports on verified number | Score capped; dispute queue item created | U |
| TC-API-007 | Bucket endpoint | Returns only entries of that bucket; ETag/304 works | U |
| TC-API-008 | Visibility | `nobody` profile never in bucket; `registered_only` only with token | U |
| TC-API-009 | Search quota | 51st search/day → 429 | U |
| TC-API-010 | Unlist | Verified unlist removes name within job run; number keeps spam score | U |
| TC-API-011 | Load | Bucket p95 < 50 ms server time with 1M rows, 200 rps | Load |

Where they run (Phase 4):

- **`./gradlew check` in `backend/server`** (CI `backend` job; Docker required): Testcontainers PostgreSQL 18, Redis 8.8 and MinIO (`cgr.dev/chainguard/minio:latest`, as in compose; `MINIO_TEST_IMAGE` overrides it) with the real Flyway migrations. 111 tests:
  - TC-API-001 (`RateLimitTest`: 4th start → 429 + `Retry-After`, plus the 10/hour/IP limit), TC-API-002 (`OtpAttemptsTest`), TC-API-003 (`RefreshRotationTest`).
  - TC-API-004 on `/v1/reports` (`ReportsApiTest.invalidNumbersAreRejected`: six malformed or invalid numbers in a batch → 400 `invalid_phone_number` naming `reports[1].e164`, nothing stored) and on the auth endpoints (`ValidationTest`). `ReportsApiTest` also covers batch size 1–50, labels, the category whitelist, comment length, the default trust row (0.5) and the 100/day limit counted per report.
  - TC-API-005 (`BayesianScorerTest`): hand-computed posteriors (written out in the test's KDoc) within ±1e-6, configurable priors and half-life, one report per reporter, category choice, §9 labels.
  - TC-API-006 (`ScoringJobTest.verifiedBusinessesAreCappedAndQueuedForReviewOnce`): 30 spam reports on a verified business number → score 0.39 (no label), one pending dispute with the automatic reason, and no second one on the next run; a business awaiting verification is scored normally. `ScoringJobTest` also covers rescoring from the watermark, time decay, indexing of users missing from the lookup index and unlist processing. `JobRunnerTest`: only one of two instances runs a job (PostgreSQL advisory lock), the lock is released after a failure.
  - TC-API-007 (`BucketLookupTest`): every 12- and 16-bit row of `fixtures/bucket_vectors.csv` (the app's `BucketHasher` vectors) is found in its bucket under its full hash; only the requested bucket and calling code are returned; ETag → 304, weak and listed validators match, new data → new ETag; invalid buckets → 400.
  - TC-API-008 (`BucketLookupTest.profileVisibilityIsRespected`): without a token only the `everyone` name; with one also `registered_only`; `nobody` never (with spam reports its number is listed without a name); `private` caching with a token.
  - TC-API-009 (`SearchApiTest`): 50 searches pass, the 51st → 429 with `Retry-After` ≈ 1 day, invalid numbers don't use the quota, each user has their own; `profile_views` row (kind `search`) unless incognito or searching your own number.
  - TC-API-010 (`UnlistTest`): unlist start → verify → the name stays until the job runs → gone from bucket and search, spam score and report count unchanged; business names are hidden too; unlist codes are separate from sign-in codes; HTML form with spaces in the number, wrong code, escaping.
  - Lists (`ListFormatTest`, `ListBuilderTest`): the encoder produces exactly the bytes of `docs/architecture.md` §13 (expected bytes made by a separate Python script written from §13); corrupt files are rejected; build v1 → no change, no new version → changes → v2 full + delta, and v1 + delta = v2; manifest, file downloads with SHA-256, ETag and immutable caching, all against a real MinIO.
  - Storage (`SigV4SignerTest`, `S3ObjectStoreTest`): AWS Signature V4 equals botocore's on `fixtures/sigv4_vectors.csv`; put/get/overwrite/missing against MinIO (which checks every signature); wrong secret and unreachable store fail.
  - Also: `DisputesApiTest`, `SyncApiTest` (seed files valid and clearly fake; `since` filtering), `AdminRoutesTest`, `AuthFlowTest` (every documented token field is sent), `ServerSchemaTest` (V1 = §7; Exposed = database after V2), `ConfigTest`, `TwilioOtpProviderTest`, `JsonLogEncoderTest`, `OpenApiSpecTest`, `HealthRoutesTest`.
- **`scripts/smoke.sh`** (CI `compose smoke` job, after `/health`; curl + jq): health and request IDs; sign-in, refresh rotation and reuse, 4th start → 429; reports (no token → 401, invalid number → 400, valid → 202); scoring run through the admin endpoint; bucket lookup of the reported number by its hash, ETag → 304, public/private caching, invalid bucket; search; list build → manifest → download and SHA-256 check; dispute; unlist through the HTML form (number typed with spaces) and the API, processed by the next job run; sender IDs and OTP sources with `since`; metrics only on the internal port, including the job metrics.
- **TC-API-011, load (manual, `backend/load/`)**: `backend/load/seed.sh` loads 1,000,000 synthetic +880 numbers (20 % with reports, 100,000 named users with mixed visibility; about 15 numbers per 16-bit bucket), then k6 runs `backend/load/bucket.js` (10 s warm-up at 50 rps, then 200 rps for 60 s on random buckets, a quarter of them with an access token) and `backend/load/server_p95.py` reads the server's own latency histogram from two `/metrics` scrapes.

TC-API-011 results:

| Date | Commit | Setup | Requests | k6 p95 / p99 / max (client) | Server p95 / p99 (histogram) | Errors | Result |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-10-17 | Phase 4 part 2 branch | Agent sandbox: compose stack (PostgreSQL 18, Redis 8.8, MinIO, server) and k6 2.3.0 on one host, 4 vCPU Xeon 2.8 GHz, 15 GB RAM; 1M seeded numbers | 12,001 measured at 200 rps (60 s) | 3.79 / 5.51 / 41.6 ms | ≤ 3.1 / ≤ 4.2 ms | 0 % | PASS (< 50 ms) |

"Server p95 ≤ x" is the upper bound of the histogram bucket that holds the 95th percentile (Micrometer percentile histogram), over all 12,501 requests including warm-up. Commands:

```bash
docker compose -f infra/docker/compose.yml up -d --build --wait
backend/load/seed.sh
docker compose -f infra/docker/compose.yml exec -T server curl -fsS localhost:9090/metrics > before.txt
docker run --rm --network host -e ACCESS_TOKEN="$TOKEN" -v "$PWD/backend/load:/load:ro" grafana/k6:2.3.0 run /load/bucket.js
docker compose -f infra/docker/compose.yml exec -T server curl -fsS localhost:9090/metrics > after.txt
python3 backend/load/server_p95.py before.txt after.txt
```

### Sync, lookup, reporting (Phase 5)

| ID | Title | Expected | Type |
| --- | --- | --- | --- |
| TC-SYNC-001 | Delta apply | v1 + delta → equals full v2 (row-for-row) | R — `core:data` `SpamListSyncTest` (v1 → v3 through two deltas equals the full v3 list row for row; the requested paths are checked) |
| TC-SYNC-002 | Corrupt download | Wrong sha256 → rejected, old list kept | R — `SpamListSyncTest` (wrong SHA-256, altered checksum trailer, wrong version: list and sync state unchanged); `core:network` `SpamListFormatTest` decodes the server's own vectors |
| TC-LOOK-001 | Lookup timeout | Server delay 2 s → call rings with local info | U + R — `ScreeningEngineTest.tcLook001SlowLiveLookupStillRingsWithLocalInfo` (engine budget) and `LiveLookupTest` (MockWebServer delays 2 s: no answer in under 1.5 s) |
| TC-LOOK-002 | Privacy | Intercept traffic during incoming call: no full number in any request | R — `core:telephony` `LookupPrivacyTest`: real engine + live lookup against MockWebServer, signed out and signed in; no path, query, header or body contains the E.164 number, its digits or its national form; the only request is `/v1/lookup/buckets/880/<bucket>?bits=16` with no body |
| TC-REP-001 | Offline report | Report while offline → synced when online | R — `ReportUploadTest` (queued against an unreachable server, sent once online with the expected JSON; waits for sign-in; honours `Retry-After`; batches of 50) |
| TC-E2E-001 | Report → block | Device A reports, job runs, device B syncs and blocks | E2E — CI job `e2e`: `scripts/e2e/report_to_block.sh` on two API 36 emulators and the compose stack. B rings before any report; A signs in (code from the server log), reports and uploads; scoring and list jobs run; B updates its list and rejects the next call (`spam_list`); a second report is caught by B's live lookup before the next list (`network`) |

Sign-in, search, disputes and the report sheet are covered by view model tests (`SignInViewModelTest`,
`SearchViewModelTest`, `DisputeViewModelTest`, `BlockingViewModelsTest`) and `WhyReasonsTest`.

Run TC-E2E-001 locally (two emulators booted as `emulator-5554` and `emulator-5556`, stack started like the compose
smoke test with `OTP_PROVIDER=log` and `ADMIN_TOKEN` set):

```bash
cd android && ./gradlew assembleFossDebug && cd ..
scripts/e2e/report_to_block.sh android/app/build/outputs/apk/foss/debug/app-foss-debug.apk io.github.jeshiomurmu.opencaller.debug
```

### Dialer / in-call / log (Phase 6)

| ID | Title | Expected | Type |
| --- | --- | --- | --- |
| TC-DIAL-001 | Place call | Number dialled through Telecom; in-call UI shows | I + U — 6a: `scripts/qa/incall.sh` (a `tel:` number opened in the dial pad, Call places it through Telecom and OpenCaller's in-call screen shows it, Hang up ends it; on the API 36 emulator the emulated network ends outgoing calls at once, so there the check is that the in-call screen opened and Telecom recorded a REMOTE disconnect, not a hang-up) and `DialPadViewModelTest` (Call goes to `TelecomManager.placeCall`, the call permission is explained first, hand-off when OpenCaller isn't the default phone app). The CI check now waits for the number formatted as typed (`+1 201-555-0198`) |
| TC-DIAL-002 | T9 search | "726" matches "Ram…" contact | U — `T9MatcherTest` (name start, later words, initials, accents and punctuation, number digits, ranking, one row per number) and `DialPadViewModelTest` (suggestions from contacts and the call log, none for codes) |
| TC-DIAL-003 | Dual SIM picker | Picker shown when no default SIM | D + U — `DialPadViewModelTest` (two SIMs and no default: the picker, and the call goes out on the chosen SIM; closing the picker calls nobody); device steps below |
| TC-DIAL-004 | USSD/MMI | `*123#` passes through to carrier | D + U — `DialStringsTest` and `DialPadViewModelTest` (codes go to `placeCall` unchanged, `*#*#n#*#*` to `sendDialerSpecialCode`); device steps below |
| TC-INCALL-001 | Answer from lock screen | Full-screen incoming UI; answer works | I + R — `scripts/qa/incall.sh` on API 29 + 36: PIN lock, screen off, `adb emu gsm call` → the in-call screen shows over the lock screen with the caller card from screening ("Suspicious"), Answer connects without unlocking, Hang up ends the call. `CallNotificationsTest` (full-screen intent, CALL category, `CallStyle` on Android 12+, Decline/Answer actions before, silent HIGH channel), `RoleEligibilityTest`, `DialerEligibilityTest` |
| TC-INCALL-002 | Hold/merge/swap | Two calls, merge into conference | D + U — logic: `InCallStateTest` (which call is in front, merge/swap decisions), `CallRegistryTest` (commands reach the right `Call`), `InCallViewModelTest`; device steps below |
| TC-INCALL-003 | Bluetooth routing | Audio switches to headset | D + U — `CallRegistryTest` (routes only to offered endpoints), `InCallViewModelTest`; device steps below |
| TC-LOG-001 | Filters | Missed / blocked / spam filters correct | U + R — `CallHistoryTest` (screening log joined within ±2 min, blocked = blocked call type or block/silence decision, spam = spam list/network reason or score ≥ 0.4, missed excludes blocked, groups per number and day, search), `CallLogViewModelTest` (filters, search, block + Undo, delete, permission gate), `DeviceCallLogTest` (Robolectric: provider mapping, hidden numbers, read only as the default phone app) |

Also covered in 6a: the after-call screen (`AfterCallTest`: a screen while the in-call screen is in front and the
phone is unlocked, otherwise the notification; Block / Not spam), the in-call caller card (`InCallCallersTest`,
`InCallDescriptionTest`: the screening answer for incoming calls, contacts / spam list / cache for outgoing ones, never
the network), Telecom constants (`TelecomMappingTest`).

Where they run (Phase 6a; 6b added the dialer, call log, contacts and quick-reply tests to the same jobs): unit and Robolectric tests in the `android` CI job; `scripts/qa/incall.sh` at the end of the
emulator job on API 29 and 36 (after `scripts/qa/blocking.sh`; it gives the debug build the default phone app role and
takes it away again). Device steps (a phone with a SIM; OpenCaller set as the default phone app):

- **TC-INCALL-002:** call the phone from a second phone, answer; tap Add call and call a third number; check the first
  call shows "On hold"; tap Swap (the calls change places), then Merge (one "Conference call · 2 people" row); hang up.
  Repeat on the second SIM of a dual-SIM phone.
- **TC-INCALL-003:** pair a Bluetooth headset; during a call tap Audio, pick the headset (Android 14+: by its name;
  before: "Bluetooth"); the audio moves to the headset and the picker marks it; switch back to Phone and Speaker.
- **TC-DIAL-003:** on a dual-SIM phone set Settings → SIM → Calls to "Ask every time"; type a number on the dial pad,
  tap Call: the "Call with" sheet lists both SIMs by name; pick the second: the call goes out on it (check the SIM shown
  in the in-call screen or the system call log). Set a default SIM: Call places at once with no sheet. Without the
  phone-numbers permission (Android 12+) no sheet shows and the in-call screen's SIM chooser asks instead.
- **TC-DIAL-004:** type the carrier's balance code (e.g. `*123#` or `*556#`) and tap Call: the carrier's reply shows as
  a system dialog; `*#06#` shows the IMEI; `*#*#4636#*#*` opens the testing menu where the device has one. Long-press 1
  calls voicemail; long-press 0 types `+`.
- **Lock screen on a phone (TC-INCALL-001 again):** with the phone locked and asleep, an incoming call shows the
  in-call screen full screen; on Android 14+ check Settings → Permissions & roles shows "Full-screen incoming calls"
  as on (Fix opens the system page when it is off).

### SMS and OTP (Phase 7)

| ID | Title | Expected | Type |
| --- | --- | --- | --- |
| TC-SMS-001 | Receive SMS as default app | Stored in provider, shown in thread, notification posted | I |
| TC-SMS-002 | Send SMS / long SMS | Multipart sent; delivery report handled | I |
| TC-SMS-003 | MMS image | Received and displayed | D |
| TC-SMS-004 | Spam classification | Corpus test split macro-F1 recorded; spam to Spam tab | U |
| TC-SMS-005 | Phishing link | Lookalike domain flagged with warning banner | U |
| TC-OTP-001 | Extract code | Precision ≥ 98%, recall ≥ 95% on held-out corpus | U |
| TC-OTP-002 | Domain-bound code | `@example.com #123456` → origin `example.com`, code `123456` | U |
| TC-OTP-003 | Registered header | `VM-HDFCBK-T` → brand from registry, category Transactional | U |
| TC-OTP-004 | App hash | Message ending in known 11-char hash → app name | U |
| TC-OTP-005 | OTP during unknown call | Active call with non-contact + OTP SMS → full-screen guard within 1 s | I |
| TC-OTP-006 | OTP during contact call | No guard (just normal OTP notification) | I |
| TC-OTP-007 | Flash call | Missed call < 3 s from verification range during unknown call → guard | D |
| TC-OTP-008 | Lock screen | OTP code hidden on lock screen notification | I |
| TC-OTP-009 | OTP burst | 3 OTPs, 2 brands, 10 min → alert | R |

### Social, business, utilities, VoIP, family, lines (Phases 8–13)

| ID | Title | Expected | Type |
| --- | --- | --- | --- |
| TC-SOC-001 | Visibility change | Name disappears from buckets after next build | E2E |
| TC-SOC-002 | Incognito search | Target's searcher list doesn't include viewer | U |
| TC-SOC-003 | Delete account | Profile gone from API immediately; purge job ≤ 30 days | U |
| TC-BIZ-001 | Claim requires ownership | Claim without OTP to that number fails | U |
| TC-BIZ-002 | Verified badge | Approved business shows badge, logo, reason on card | I |
| TC-UT-001 | Ghost call | Scheduled fake call appears with chosen name/photo; no real call placed | I |
| TC-UT-002 | Announce caller | TTS speaks resolved name; "headphones only" respected | I |
| TC-UT-003 | Backup/restore | Rules + settings restored identically on fresh install | I |
| TC-UT-004 | WhatsApp caller ID | Notification from WhatsApp incoming call with number → card | D |
| TC-BILL-001 | Purchase | License tester buys Supporter → entitlement active after server verify | D |
| TC-VOIP-001 | App-to-app call | Wi-Fi ↔ mobile data, setup < 3 s p95, audio both ways | D |
| TC-FAM-001 | Consent | Member must accept on own device; admin can't add silently | I |
| TC-FAM-002 | Family alert | Member OTP-during-call → admin notified within 10 s | E2E |
| TC-REC-001 | Recording beep | Other party hears beep; file on device; server copy deleted ≤ 1 h | D |
| TC-AST-001 | Assistant | Forwarded unanswered call answered by agent; transcript streams | D |
| TC-SCAN-001 | Scanner wording | Result phrased as likelihood, never certainty | U |

## 5. Manual QA checklist (before each release)

1. Fresh install on Android 10, 13, 16 emulators and one physical phone (dual SIM preferred).
2. Onboarding with every permission denied once, then granted.
3. 20 incoming calls from the QA list (contacts, spam, business, unknown, foreign) — record decision + latency from debug screen.
4. 30 SMS from the QA corpus incl. 10 OTP formats; OTP guard during a call.
5. Airplane mode: blocking still works; reports queue.
6. Battery: 24 h idle with 10 calls/20 SMS; app < 1% drain.
7. TalkBack pass over caller card, OTP guard, blocked log.
8. Font scale 200%: no clipped text on 10 key screens.
9. Upgrade test from previous release (DB migration, settings kept).
10. Network capture during calls/SMS: verify no contact, call-log or SMS body data leaves the device.

## 6. Bug log

Format: one row per bug, newest first. Severity: S1 crash/data loss/privacy leak, S2 core feature broken, S3 degraded, S4 cosmetic. Agents: when stuck after 3 attempts, log here with what you tried.

| ID | Date | Sev | Module | Summary | Repro | Status | Fix / notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| BUG-0002 | 2026-10-16 | S3 | core:telephony | Emulator p95 local decision latency sits at the 50 ms gate (Phase 3 exit criterion) | `scripts/qa/blocking.sh` in CI, 33 calls, debug screen | fixed (CI gate) — phone check open | Tried: (1) `ScreeningWarmUp` loads the home country's numbering metadata at start (removed an 85 ms first call); (2) local lookups started concurrently; this exposed a ~0.5 s stall at the same call on both emulators (526/527 ms, matches opening another SQLCipher connection); (3) database reads chained (stall gone, max 68 ms); (4) 33-call sample and per-step timings (run 38899690568). Result: API 29 p95 46 ms, API 36 p95 50 ms. The step timings show the system contacts lookup (`PhoneLookup`, a call into the contacts provider) at 24–58 ms on the slow decisions; the app's own steps are 0–16 ms. Fix (option a, 2026-10-16): CI gates the app's own time (decision minus the contacts lookup) at p95 < 50 ms and the emulator total at p95 < 100 ms; the 50 ms total is measured on a phone (manual step under "Where they run (Phase 3)"). Same commit had passed and failed the old gate (`d3bf5ea`: push run green, PR run red) |
| BUG-0001 | | | | _example_ Caller card missing on Android 10 when overlay denied | TC-ONB-007 | open | |

## 7. Known platform limitations (not bugs)

- A call screening app that is not the default phone app never sees hidden-number calls: Android doesn't pass calls with restricted, unknown, unavailable or payphone presentation to `CallScreeningService` (developer.android.com, `CallScreeningService.onScreenCall`). "Block hidden numbers" therefore works once OpenCaller is the default phone app (Phase 6); the setting says so.
- `CallResponse.Builder.setSkipCallLog` is ignored for apps other than the system dialer, so blocked calls still appear in the system call log.
- Android's `java.util.regex` is ICU and doesn't read its input through `CharSequence.charAt`, so a deadline-checking `CharSequence` can't stop a runaway pattern there. Regex rules therefore also run on a watchdog thread with a hard time limit (`TimeLimitedRegex`), and the editor rejects slow patterns before they are saved.

- Native call recording is not available to third-party apps; recording uses the recording line (PRD F-UT-03).
- Conditional call forwarding codes differ by carrier; some carriers don't support them.
- iOS is out of scope.
