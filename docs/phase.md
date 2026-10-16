# Current phase — OpenCaller

> Agents: read this first. Update it at the end of every task (tick items, add a log line, set "Next step", list open issues). Keep the log newest-first and under ~60 lines; move older entries to `docs/phase-history.md`.

## Status

| Field | Value |
| --- | --- |
| **Current phase** | **3 — Call screening + local blocking** |
| Phase started | 2026-10-16 |
| Target exit date | — |
| Branch | `phase/03-call-screening` |
| Last green CI run | Phase 3: [run 38894580688](https://github.com/jeshiomurmu/OpenCaller/actions/runs/38894580688) on `74d55ce` (`phase/03-call-screening`), 2026-10-16, all 6 jobs green, blocking QA on API 29 + 36. Phase 2: [run 38879129151](https://github.com/jeshiomurmu/OpenCaller/actions/runs/38879129151) on `f6b7392` (`phase/02-onboarding`, PR #3), 2026-10-16, all 6 jobs green. `main`: [run 38867777494](https://github.com/jeshiomurmu/OpenCaller/actions/runs/38867777494) (`aede43d`) |
| App version | 0.0.0 |

## Current phase checklist

Copied from `docs/implementation_plan.md` → Phase 3. Tick here as you go; copy the ticked list back to the plan when the phase exits.

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

**Exit criteria:** with `adb emu gsm call`, every row in `testing.md` TC-BLK-* passes; p95 decision latency < 50 ms for local decisions (measured in debug screen); screening exception test proves fail-open.

## Next step

Review and merge the Phase 3 PR (squash; the checklist is already ticked in `docs/implementation_plan.md`), confirm CI is green on `main`, then **run P-04** (backend MVP).

Exit criteria status (CI run 38899690568 on `b2a69da`, `scripts/qa/blocking.sh` on API 29 and API 36):
- `adb emu gsm call` cases: TC-BLK-001, 002, 003, 006, 008, 009, 012 and 015 pass on both emulators, plus TC-BLK-004 against the device's own regex engine.
- Unit/Robolectric by nature: TC-BLK-005, 010, 011 and 013. Device only: TC-BLK-007 and TC-BLK-014 (issues 25 and 33).
- Latency: the old CI gate (total p95 < 50 ms on the emulator) failed at random because Android's contacts lookup alone takes 20–90 ms on the CI emulators; the same commit passed and failed it (BUG-0002, issue 36). CI now gates the app's own time (p95 < 50 ms) and the emulator total (p95 < 100 ms). **Still to do: measure the total p95 < 50 ms on a phone** (manual step in `docs/testing.md`).
- Fail-open: `ScreeningEngineTest` (an injected exception ends in ALLOW with reason `error`; a 3 s lookup ends in ALLOW at the 1,500 ms budget; a failed lookup only counts when the decision needs it) and `ScreeningCoordinatorTest` ✅.

## Open issues / blockers

1. **Repository visibility.** `jeshiomurmu/OpenCaller` is public, but the plan says private until v0.1. The owner decides (GitHub → Settings → Danger zone → Change visibility).
2. **MinIO distribution.** MinIO no longer publishes images and its repo is archived. The stack uses `cgr.dev/chainguard/minio:latest` (free tier, `latest` only, not pinnable) plus a static BusyBox for the healthcheck (`infra/docker/minio/Dockerfile`). It is verified in CI. Long term: pin a digest/paid tag, build from source, or move to an S3-compatible alternative (Garage/SeaweedFS) via an ADR before Phase 4.
3. **Compose is held at BOM 2026.06.01 (Compose 1.11.x).** Compose 1.12+ requires `compileSdk 37`, and `architecture.md` §2 fixes `compileSdk 36`. Raising compileSdk to 37 (targetSdk can stay 36) would allow current Compose; needs a decision/ADR.
4. **Lint `OldTargetApi` disabled.** Android 17 (API 37) is out; `targetSdk 36` is fixed by `architecture.md` §2. Revisit before the first Play release (Phase 15).
5. **Detekt 1.23.8 Gradle plugin** triggers Gradle 10/11 deprecation warnings (`ReportingExtension.file`, `Configuration.setVisible`). Detekt 2.0 is still alpha; upgrade when it is stable.
6. **Room 3.** Room 3.x exists but drops the SupportSQLite APIs that SQLCipher's `SupportOpenHelperFactory` plugs into, so the project stays on Room 2.8.5. Revisit when SQLCipher ships a Room 3 driver (needs an ADR).
7. **Gradle 9.8.1** is used for both builds. AGP 9.4.0 declares Gradle ≥ 9.6.0 (minimum and default 9.6.0).
8. **Agent sandbox limits** (informational): `dl.google.com`, `github.com`, `www.gnu.org` and `cgr.dev` are blocked and there is no KVM, so Android, emulator and LICENSE checks run only in CI. JVM modules (`core:common`, `core:model`, `core:phonenumber`, `core:testing`, proto mapping) and the backend were also run locally.
9. **`RedactingTree` location.** The spec puts it in `core:common`, but Timber is an Android library and `core:common` must not depend on Android. `LogRedactor` (the masking) is in `core:common`; the `Timber.Tree` wrapper is in `app` (`logging/RedactingTree.kt`). Move it into a small Android module if features need to plant it themselves.
10. **Wrong SQLCipher key wipes the DB.** Android's default `DatabaseErrorHandler` deletes a database file it cannot open. If the Keystore key is lost (e.g. a device restore without the key), the user's block rules and local data are recreated empty instead of being kept. Decide before Phase 3 whether to surface this (custom error handler + "reset local data" UX) or accept it.
11. **compileSdk 37 also holds back Navigation.** Navigation 2.10 needs `compileSdk 37` (same as issue 3), so Navigation Compose is pinned at 2.9.8.
12. **Default region.** Resolved in Phase 3: `DeviceHomeRegionProvider` (`core:telephony`) reads the SIM country, then the network country, then the locale.
13. **Tab naming.** `implementation_plan.md` says "Blocking"; P-01 and the app say "Protection". Pick one and align the docs before Phase 3.
14. **Derived colour roles.** `design.md` §2 doesn't define every Material 3 role (e.g. dark semantic containers, `surfaceContainer*`). These are derived by blending §2 tokens (`ColorScheme.kt`, `OpenCallerSemanticColors.kt`). A designer should confirm the result.
15. **Module types.** `core:phonenumber`, `core:testing` and `core:datastore` are JVM modules (no Android API needed), so they test without Robolectric. `core:database`, `core:designsystem` and `core:ui` are Android libraries.
16. **API 29 emulator night mode.** `cmd uimode night yes` from the shell has no effect on the API 29 image. `scripts/ci/emulator-smoke.sh` retries as root and then falls back to the persisted setting plus a reboot.
17. **Default phone and SMS app roles show "Coming in a later update".** Android grants `ROLE_DIALER` / `ROLE_SMS` only to apps that ship an in-call UI / SMS receivers; stubbing them now would leave calls without an in-call screen or drop incoming SMS. `RoleEligibility` checks the app's own manifest, so both turn on automatically when Phases 6 and 7 add those components. TC-ONB-003's card shows the "later" text until then.
18. **Role changes kill the app process on API 36.** Granting or revoking `ROLE_CALL_SCREENING` (system dialog or `cmd role`, even with the don't-kill flag) ended the app process, which in instrumented tests is also the test process. TC-ONB-001, 003 (role held) and 005 therefore run in `scripts/ci/emulator-smoke.sh` via adb. The app copes (the step is persisted and onboarding resumes). Re-check on a physical Android 16 phone before Phase 3.
19. **Robolectric runs SDK 35, not 36.** Robolectric 4.17's Android 16 image needs Java 21 (`DefaultSdkProvider`); the project builds with JDK 17. Move tests to SDK 36 when the toolchain moves to Java 21 (needs a decision).
20. **TC-ONB-006 / 007 against the real surfaces.** Phase 3 added the caller ID card and the after-call notification. The emulator QA shows the overlay card (TC-BLK-009). `CallerIdTest` (Robolectric) covers the heads-up fallback and that nothing is posted without the notification permission. A manual pass with the overlay denied on a phone is still worth doing.
21. **Not yet requested:** `WRITE_CONTACTS` (contacts editing, Phase 6) and `READ_PHONE_NUMBERS`. `READ_PHONE_STATE` is requested from Settings since Phase 3 (issue 28). `architecture.md` §5 lists them for onboarding; add them with the screens that need them.
22. **Test fake duplicated.** `FakeAccessStatusSource` exists in three test source sets (onboarding, settings, app) because `core:testing` is a JVM module and `AccessStatusSource` lives in the Android `core:telephony`. Consider Android test fixtures later.
23. **Maven Central returned HTTP 403** for the Robolectric/Turbine POMs in one CI run (run 38871676332, 2026-10-16); the next run resolved them. Watch for repeats: the branch's Gradle cache is read-only, so new dependencies are downloaded on every run.
24. **Spam threshold semantics.** The Phase 3 spec fixes HIGH = 0.85 and MEDIUM = 0.6, so a higher threshold blocks *fewer* calls (the old `SpamThreshold` KDoc said the opposite and was rewritten). LOW = 0.4 is our choice (not in the spec); numbers listed from 0.4 up to the threshold show as Suspicious. Confirm LOW with the product owner.
25. **Hidden numbers (TC-BLK-007).** Android doesn't pass hidden-number calls to a call screening app that isn't the default phone app, so "Block hidden numbers" only takes effect once OpenCaller can be the default phone app (Phase 6). The setting says so; device-only test.
26. **Stubs until later phases:** `NetworkLookup` is `NoNetworkLookup` (Phase 5), `VerifiedBusinessLookup` returns false except for numbers the debug QA script marks (Phase 9), spam/not-spam reports stay in `report_outbox` (Phase 5 sends them).
27. **After-call summary in screening-only mode is best effort.** Without the overlay, nothing keeps the process alive while the phone rings, so Android may kill it before the call ends and no summary is posted. A foreground service would need a `phoneCall` type, which requires the default phone app role (Phase 6).
28. **Call status permission.** `READ_PHONE_STATE` (Android 12+) is requested from Settings → Permissions & roles ("Call status"), not in onboarding. Without it the card closes when the audio mode returns to normal, or after 90 s for silenced calls.
29. **Regex rules on Android.** The platform regex engine (ICU) can't be interrupted, so a runaway pattern keeps one of 4 daemon threads busy until it finishes (bounded by the 16-character input); the editor rejects slow patterns and a pattern that times out twice is skipped.
30. **"Not spam" from the after-call notification** adds an exact allow rule and queues a NOT_SPAM report. Revisit in Phase 5 once community scores exist (an allow rule also overrides the user's own later block rules for that number).
31. **Screening log retention** is 90 days, with no "clear history" action yet.
32. **Emulator memory.** The API 29 image ran `system_server` with a 16 MB heap and died mid-test once (run 38891529407); the CI emulators now get `ram-size: 4096M` and `heap-size: 512M`. Watch for repeats.

33. **STIR/SHAKEN (TC-BLK-014)** is read on API 30+ and mapped to the card ("Verified by carrier" / "Possibly spoofed"). The emulator can't sign calls, so it is tested at the mapping level only (`DeviceScreeningTest`); check on a carrier that signs calls.
34. **First calls.** libphonenumber loads each country's metadata on first use. `ScreeningWarmUp` loads the home country at app start and on a SIM change, but the first call from a rarely seen country can still take tens of ms (about 70 ms observed for a cold country before the warm-up). That stays far inside the 1,500 ms budget. Opening another encrypted database connection costs about 0.5 s (SQLCipher derives the key per connection), so the engine reads its lists one after another; a raw 256-bit key would skip the derivation (changes `core:database`, needs a decision).
35. **Seed spam list is empty.** `assets/spam_list/seed.csv` has only comments; tests use `core/testing/.../fixtures/spam_list_small.csv`. Phase 5 fills `spam_list` from the server.
36. **Decision latency on CI emulators (BUG-0002).** Debug builds log each decision's step durations and the QA script prints the slowest. The app's own steps take 0–16 ms; Android's contacts lookup (`ContactsContract.PhoneLookup`, a call into the system contacts provider, required before any block because contacts always ring) takes 24–58 ms on the slow decisions, so p95 sits at 46–50 ms against a "< 50 ms" gate. Decided 2026-10-16 (owner asked to fix the failing check; option (a) of the three proposed): CI gates the app's own time (decision minus the contacts lookup) at p95 < 50 ms and the emulator total at p95 < 100 ms; the exit criterion's total < 50 ms is measured on a physical phone. Not chosen: an in-memory contact index (deviates from the `PhoneLookup` spec, can miss contacts saved seconds before a call; would need an ADR).

## Verified dependency versions

Fill as dependencies are added (the source of truth is `libs.versions.toml`; this table records *where* each version was verified and when). "Latest stable" means the newest non-alpha/beta/RC version on the source at that date.

| Library | Version | Verified at (URL) | Date |
| --- | --- | --- | --- |
| Android Gradle Plugin | 9.4.0 (needs Gradle ≥ 9.6.0, JDK 17) | https://developer.android.com/build/releases/gradle-plugin | 2026-10-16 |
| Gradle (both builds) | 9.8.1, bin sha256 `dce76f55…dc01f`, wrapper JAR sha256 `3b8a2577…8e98` | https://services.gradle.org/versions/current, https://gradle.org/release-checksums/ | 2026-10-16 |
| Kotlin (+ compose compiler, serialization plugin, kotlin-metadata-jvm) | 2.4.21 | Maven Central `org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml` (via Google's Central mirror) | 2026-10-16 |
| KSP | 2.3.12 | Maven Central `com/google/devtools/ksp/.../maven-metadata.xml`; https://github.com/google/ksp/releases (2.3.10: AGP 9 built-in Kotlin + Kotlin 2.4 fixes) | 2026-10-16 |
| Hilt / Dagger | 2.60.1 | Maven Central `com/google/dagger/hilt-android/maven-metadata.xml`; https://github.com/google/dagger/releases (≥ 2.59.1 requires AGP 9) | 2026-10-16 |
| Room (runtime, compiler, Gradle plugin) | 2.8.5 | https://developer.android.com/jetpack/androidx/releases/room | 2026-10-16 |
| Compose BOM | 2026.06.01 (Compose 1.11.x, Material 3 1.4.0). 2026.09.00 / Compose 1.12.1 rejected: requires compileSdk 37 ("Updated Compose compileSdk to API 37", 1.12.0-alpha01) | https://developer.android.com/develop/ui/compose/bom/bom-mapping, https://developer.android.com/jetpack/androidx/releases/compose-ui, https://developer.android.com/jetpack/androidx/releases/compose | 2026-10-16 |
| AndroidX Activity (activity-compose) | 1.13.0 | https://developer.android.com/jetpack/androidx/releases/activity | 2026-10-16 |
| JUnit | 4.13.2 | Maven Central `junit/junit/maven-metadata.xml` | 2026-10-16 |
| Spotless Gradle plugin | 8.10.4 | https://plugins.gradle.org/m2/com/diffplug/spotless/com.diffplug.spotless.gradle.plugin/maven-metadata.xml | 2026-10-16 |
| ktlint | 1.8.0 | Maven Central `com/pinterest/ktlint/ktlint-cli/maven-metadata.xml` | 2026-10-16 |
| Detekt | 1.23.8 (2.0 only alpha) | Maven Central + Gradle Plugin Portal metadata | 2026-10-16 |
| Ktor (server core, Netty, content negotiation, kotlinx-json, test host) | 3.6.0 | Maven Central `io/ktor/ktor-server-core/maven-metadata.xml` | 2026-10-16 |
| Logback | 1.6.5 | Maven Central `ch/qos/logback/logback-classic/maven-metadata.xml` | 2026-10-16 |
| actions/checkout | v7 (v7.0.1) | https://github.com/actions/checkout (README, releases) | 2026-10-16 |
| actions/setup-java | v6 | https://github.com/actions/setup-java (README) | 2026-10-16 |
| gradle/actions/setup-gradle | v6 | https://github.com/gradle/actions (README, docs/setup-gradle.md) | 2026-10-16 |
| Docker images | `postgres:18`, `redis:8.8`, `eclipse-temurin:17-jdk-noble` / `17-jre-noble`, `busybox:stable-musl` (1.36, static) , `cgr.dev/chainguard/minio:latest` | https://hub.docker.com (tag API); MinIO RELEASE.2026-09-22 observed in CI | 2026-10-16 |
| reactivecircus/android-emulator-runner | v2 | https://github.com/ReactiveCircus/android-emulator-runner (README) | 2026-10-16 |
| actions/upload-artifact | v7 | https://github.com/actions/upload-artifact (README) | 2026-10-16 |
| GPL-3.0 text | sha256 `3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986` (matched a live gnu.org download in CI) | https://www.gnu.org/licenses/gpl-3.0.txt | 2026-10-16 |
| libphonenumber | 9.0.41 | Maven Central `com/googlecode/libphonenumber/libphonenumber/maven-metadata.xml` | 2026-10-16 |
| kotlinx-coroutines (core, android, test) | 1.11.0 | Maven Central `org/jetbrains/kotlinx/kotlinx-coroutines-core/maven-metadata.xml` | 2026-10-16 |
| kotlinx-serialization-core | 1.11.0 | Maven Central `org/jetbrains/kotlinx/kotlinx-serialization-core/maven-metadata.xml` | 2026-10-16 |
| Timber | 5.0.1 | Maven Central `com/jakewharton/timber/timber/maven-metadata.xml` | 2026-10-16 |
| protobuf (kotlin-lite, protoc) | 4.36.2 | Maven Central `com/google/protobuf/protobuf-kotlin-lite/maven-metadata.xml`, `.../protoc/maven-metadata.xml` | 2026-10-16 |
| protobuf Gradle plugin | 0.10.0 | Gradle Plugin Portal `com/google/protobuf/com.google.protobuf.gradle.plugin/maven-metadata.xml` | 2026-10-16 |
| SQLCipher for Android (`net.zetetic:sqlcipher-android`) | 4.19.1 | Maven Central `net/zetetic/sqlcipher-android/maven-metadata.xml` | 2026-10-16 |
| AndroidX SQLite (`sqlite`, `sqlite-framework`) | 2.7.1 | https://developer.android.com/jetpack/androidx/releases/sqlite | 2026-10-16 |
| AndroidX DataStore (core, preferences) | 1.2.1 | https://developer.android.com/jetpack/androidx/releases/datastore | 2026-10-16 |
| Navigation Compose | 2.9.8 (2.10 requires compileSdk 37) | https://developer.android.com/jetpack/androidx/releases/navigation | 2026-10-16 |
| AndroidX Test (core 1.7.0, runner 1.7.0, ext-junit 1.3.0) | see left | https://developer.android.com/jetpack/androidx/releases/test | 2026-10-16 |
| JaCoCo (Gradle `jacoco` plugin tool version) | 0.8.15 | Maven Central `org/jacoco/org.jacoco.agent/maven-metadata.xml` | 2026-10-16 |
| Noto Sans / Noto Sans Mono fonts | 2.015 / 2.014 (OFL-1.1) | https://github.com/notofonts/latin-greek-cyrillic, https://raw.githubusercontent.com/google/fonts/main/ofl/notosans/METADATA | 2026-10-16 |
| Material Symbols (16 vector icons; Phase 2 added notifications, lock, check_circle, layers) | `master` of google/material-design-icons (Apache-2.0); `material-icons-core` is frozen at 1.7.8, so not used | https://github.com/google/material-design-icons | 2026-10-16 |
| AndroidX Lifecycle (common, runtime-compose, viewmodel-compose, viewmodel-savedstate) | 2.10.0 (2.11.0 moved Compose to compileSdk 37, 2.11.0-beta01 notes) | https://developer.android.com/jetpack/androidx/releases/lifecycle | 2026-10-16 |
| AndroidX Hilt (`hilt-lifecycle-viewmodel-compose`) | 1.3.0 (1.4.0 moved Compose to compileSdk 37, 1.4.0-beta01 notes) | https://developer.android.com/jetpack/androidx/releases/hilt | 2026-10-16 |
| Robolectric | 4.17 (tests use SDK 29 and 35; SDK 36 needs Java 21) | Maven Central `org/robolectric/robolectric/maven-metadata.xml`; `DefaultSdkProvider` in the 4.17 jar | 2026-10-16 |
| AndroidX Test UiAutomator | 2.4.0 | https://developer.android.com/jetpack/androidx/releases/test-uiautomator | 2026-10-16 |
| AndroidX Test Orchestrator / Test Services | 1.6.1 / 1.6.0 | https://developer.android.com/jetpack/androidx/releases/test | 2026-10-16 |
| AndroidX SavedState (`savedstate`, caller-card overlay window) | 1.5.0 (released 2026-05-19, before Lifecycle 2.11 / compileSdk 37) | https://developer.android.com/jetpack/androidx/releases/savedstate | 2026-10-16 |
| AndroidX Lifecycle `lifecycle-runtime` (overlay window's LifecycleRegistry) | 2.10.0 (same release as the other Lifecycle artifacts) | https://developer.android.com/jetpack/androidx/releases/lifecycle | 2026-10-16 |

## Progress log (newest first)

| Date | Phase | What changed | Verified by |
| --- | --- | --- | --- |
| 2026-10-16 | 3 | Phase 3 call screening: `core:model` screening types; `core:data` repositories + spam-list seeder; `core:telephony` `ScreeningEngine` (§4.1 order, 1,500 ms budget, fail-open, time-limited regex), `RuleValidator`, `OpenCallerScreeningService` + `CallResponse` mapping, contacts/region/emergency adapters, STIR mapping, warm-up; `core:ui` `CallerCard` (all states, previews); `feature:callerid` overlay + heads-up + after-call notification; `feature:blocking` settings, rules editor with Test a number, blocked-calls log with Unblock/Undo; `feature:settings` debug screen (About ×7, last 100 events, p50/p95). Debug-only QA receiver + export; `scripts/qa/blocking.sh` in CI | CI run 38894580688 green (6 jobs): android (spotless, detekt, lint, unit + Robolectric ×2 flavors, assemble ×2, `check`); emulator API 29 + 36: onboarding smoke, then blocking QA TC-BLK-002/003/004/006/008/009/012/015 PASS, debug-screen p95 43 ms (API 36) / 20 ms (API 29); backend; compose smoke; license |
| 2026-10-16 | 2 | Phase 2 onboarding (PR #3): access model types; `core:telephony` (`RoleManagerHelper`, `PermissionStatusReader`, `AccessStatusSource` refreshed on resume, `PermissionPolicy`, `AccessRequestFactory`, pass-through `CallScreeningService`); `core:ui` explainers, `WarningBanner`, request launcher; `feature:onboarding` (5 steps, persisted); `feature:settings` (Permissions & roles with Fix); app: degraded-mode banner, Calls card, Me → Settings. CI: app instrumented tests with the orchestrator; smoke script drives role grant/revoke and process death | CI run 38879129151 green: android (spotless, detekt, lint, unit + Robolectric tests ×2 flavors, assemble ×2, `check`), emulator API 29 + 36 (DB tests; `OnboardingTest` TC-ONB-002/003/006/007/008; smoke TC-ONB-008/001/003/005 + light/dark tab walk), backend, compose smoke, license |
| 2026-10-16 | 1 | Phase 1 foundations (PR #2): `core:common`, `core:model`, `core:phonenumber` (+ backend `BucketHasher` with shared vectors), `core:database` (Room + SQLCipher, Keystore-wrapped passphrase, schema v1), `core:datastore` (proto), `core:designsystem` (tokens, Noto Sans, icons), `core:ui` components, `core:testing`, app shell with 5 tabs. CI: Room schema drift check, instrumented DB tests and a light/dark tab walk on API 29 + 36 | CI on PR #2 (see Status): spotless, detekt, lint, unit tests ×2 flavors, assemble ×2, `check` (JaCoCo ≥ 90 % on `core:phonenumber`), backend `check` + vector `cmp`, `connectedDebugAndroidTest` on API 29 + 36, emulator walk of all 5 tabs light + dark. Locally: 41 JVM tests, coverage 98.3 %, backend 4 tests |
| 2026-10-16 | 0 | Push access restored; PR #1 opened. CI fixes: feature guard allowed own path, Compose BOM → 2026.06.01 (compileSdk 36), `fullBackupContent` for API 29–30, MinIO healthcheck via static BusyBox. Added LICENSE + CI jobs `emulator` (API 29/36), `compose smoke`, `license` | CI green: android (spotless, detekt, lint ×34, unit tests ×2 flavors, assemble ×2, `check`), backend, emulator API 29 + 36 ("OpenCaller" on screen), compose smoke (`/health` 200), license (matches gnu.org) |
| 2026-10-16 | 0 | Bootstrapped `android/` (catalog, 7 convention plugins, 35 modules, flavors, Compose MainActivity, Spotless/Detekt/Lint), `backend/server/` (Ktor `/health`, Dockerfile), `infra/docker/compose.yml`, `ci.yml`, CHANGELOG, NOTICE; ADRs reviewed | Backend `./gradlew check` green; compose up, all healthy, `/health` 200; Spotless + Detekt on Android sources green. Android build/CI not yet run at that point |
| 2026-10-15 | — | Planning docs created | Human review |

## Phase history

| Phase | Started | Exited | Notes |
| --- | --- | --- | --- |
| 2 — Onboarding, roles, permissions | 2026-10-16 | 2026-10-16 | PR #3 merged (`4b1ef03`). Main CI run 38882608702: android, backend, compose, license and emulator API 29 green; emulator API 36 failed once in TC-ONB-006 (a deny tap on the notification dialog landed during its open animation and was ignored). Fixed on `phase/03-call-screening` (`tapSystemButton` retries until the button is gone) |
| 1 — Core foundations | 2026-10-16 | 2026-10-16 | PR #2 merged; CI green on `main` (`aede43d`, run 38867777494); `core:phonenumber` coverage ≥ 90 %; encrypted DB verified on API 29 + 36; 5 tabs walked in light/dark on API 29 + 36 |
| 0 — Repo, tooling, CI | 2026-10-16 | 2026-10-16 | PR #1 merged; CI green on `main` (`2c21f97`); emulator install/launch API 29 + 36; `/health` 200. Open: repo is public (plan says private until v0.1) |
