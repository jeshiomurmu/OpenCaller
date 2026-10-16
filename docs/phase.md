# Current phase — OpenCaller

> Agents: read this first. Update it at the end of every task (tick items, add a log line, set "Next step", list open issues). Keep the log newest-first and under ~60 lines; move older entries to `docs/phase-history.md`.

## Status

| Field | Value |
| --- | --- |
| **Current phase** | **2 — Onboarding, roles, permissions** |
| Phase started | 2026-10-16 |
| Target exit date | — |
| Branch | `phase/02-onboarding` |
| Last green CI run | [CI on `main`](https://github.com/jeshiomurmu/OpenCaller/actions/runs/38867777494) (`aede43d`, Phase 1 merge), 2026-10-16, all 6 jobs green |
| App version | 0.0.0 |

## Current phase checklist

Copied from `docs/implementation_plan.md` → Phase 2. Tick here as you go; copy the ticked list back to the plan when the phase exits.

- [ ] Welcome + privacy explainer screens (what leaves the phone, what never does)
- [ ] `RoleManagerHelper`: query/request `ROLE_CALL_SCREENING`, `ROLE_DIALER`, `ROLE_SMS`
- [ ] Permission explainers + requests per `architecture.md` §5 (only after the matching role)
- [ ] Overlay permission flow (`SYSTEM_ALERT_WINDOW`)
- [ ] Notification permission (API 33+)
- [ ] Onboarding state persisted; re-entry from Settings → Permissions status screen
- [ ] Handle "role revoked later" (re-check on resume, banner)

**Exit criteria:** on a fresh emulator, onboarding grants call-screening role; denying each permission shows the degraded-mode banner, never a crash (TC-ONB-001…008).

## Next step

Phase 2 in progress (prompt P-02).

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
12. **Default region.** `PhoneNumberNormalizer` takes `defaultRegion` explicitly; reading it from the SIM/network (with a locale fallback) belongs to `core:telephony` (no telephony work in Phase 1).
13. **Tab naming.** `implementation_plan.md` says "Blocking"; P-01 and the app say "Protection". Pick one and align the docs before Phase 3.
14. **Derived colour roles.** `design.md` §2 doesn't define every Material 3 role (e.g. dark semantic containers, `surfaceContainer*`). These are derived by blending §2 tokens (`ColorScheme.kt`, `OpenCallerSemanticColors.kt`). A designer should confirm the result.
15. **Module types.** `core:phonenumber`, `core:testing` and `core:datastore` are JVM modules (no Android API needed), so they test without Robolectric. `core:database`, `core:designsystem` and `core:ui` are Android libraries.
16. **API 29 emulator night mode.** `cmd uimode night yes` from the shell has no effect on the API 29 image. `scripts/ci/emulator-smoke.sh` retries as root and then falls back to the persisted setting plus a reboot.

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
| Material Symbols (12 vector icons) | `master` of google/material-design-icons (Apache-2.0); `material-icons-core` is frozen at 1.7.8, so not used | https://github.com/google/material-design-icons | 2026-10-16 |

## Progress log (newest first)

| Date | Phase | What changed | Verified by |
| --- | --- | --- | --- |
| 2026-10-16 | 1 | Phase 1 foundations (PR #2): `core:common`, `core:model`, `core:phonenumber` (+ backend `BucketHasher` with shared vectors), `core:database` (Room + SQLCipher, Keystore-wrapped passphrase, schema v1), `core:datastore` (proto), `core:designsystem` (tokens, Noto Sans, icons), `core:ui` components, `core:testing`, app shell with 5 tabs. CI: Room schema drift check, instrumented DB tests and a light/dark tab walk on API 29 + 36 | CI on PR #2 (see Status): spotless, detekt, lint, unit tests ×2 flavors, assemble ×2, `check` (JaCoCo ≥ 90 % on `core:phonenumber`), backend `check` + vector `cmp`, `connectedDebugAndroidTest` on API 29 + 36, emulator walk of all 5 tabs light + dark. Locally: 41 JVM tests, coverage 98.3 %, backend 4 tests |
| 2026-10-16 | 0 | Push access restored; PR #1 opened. CI fixes: feature guard allowed own path, Compose BOM → 2026.06.01 (compileSdk 36), `fullBackupContent` for API 29–30, MinIO healthcheck via static BusyBox. Added LICENSE + CI jobs `emulator` (API 29/36), `compose smoke`, `license` | CI green: android (spotless, detekt, lint ×34, unit tests ×2 flavors, assemble ×2, `check`), backend, emulator API 29 + 36 ("OpenCaller" on screen), compose smoke (`/health` 200), license (matches gnu.org) |
| 2026-10-16 | 0 | Bootstrapped `android/` (catalog, 7 convention plugins, 35 modules, flavors, Compose MainActivity, Spotless/Detekt/Lint), `backend/server/` (Ktor `/health`, Dockerfile), `infra/docker/compose.yml`, `ci.yml`, CHANGELOG, NOTICE; ADRs reviewed | Backend `./gradlew check` green; compose up, all healthy, `/health` 200; Spotless + Detekt on Android sources green. Android build/CI not yet run at that point |
| 2026-10-15 | — | Planning docs created | Human review |

## Phase history

| Phase | Started | Exited | Notes |
| --- | --- | --- | --- |
| 1 — Core foundations | 2026-10-16 | 2026-10-16 | PR #2 merged; CI green on `main` (`aede43d`, run 38867777494); `core:phonenumber` coverage ≥ 90 %; encrypted DB verified on API 29 + 36; 5 tabs walked in light/dark on API 29 + 36 |
| 0 — Repo, tooling, CI | 2026-10-16 | 2026-10-16 | PR #1 merged; CI green on `main` (`2c21f97`); emulator install/launch API 29 + 36; `/health` 200. Open: repo is public (plan says private until v0.1) |
