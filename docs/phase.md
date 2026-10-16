# Current phase — OpenCaller

> Agents: read this first. Update it at the end of every task (tick items, add a log line, set "Next step", list open issues). Keep the log newest-first and under ~60 lines; move older entries to `docs/phase-history.md`.

## Status

| Field | Value |
| --- | --- |
| **Current phase** | **0 — Repo, tooling, CI** |
| Phase started | 2026-10-16 |
| Target exit date | — |
| Branch | `phase/00-tooling` |
| Last green CI run | — |
| App version | 0.0.0 |

## Current phase checklist

Copied from `docs/implementation_plan.md` → Phase 0. Tick here as you go; copy the ticked list back to the plan when the phase exits.

- [ ] `git init`, `main` branch, push to GitHub `jeshiomurmu/opencaller` (private until v0.1) — `main` exists on GitHub; `phase/00-tooling` committed locally but **push refused (403)**, see Open issues
- [ ] Add `LICENSE` (GPL-3.0 full text from gnu.org) and `CHANGELOG.md` — `CHANGELOG.md` and `NOTICE.md` added; **`LICENSE` missing** (gnu.org unreachable from the agent sandbox)
- [ ] Create Android Gradle project in `android/` with version catalog and `build-logic` convention plugins — written; Spotless + Detekt pass locally; Gradle build not yet run (needs CI)
- [ ] Create empty modules from `architecture.md` §3 and wire them in `settings.gradle.kts` — written (12 core + 21 feature); not yet built
- [ ] Flavors `foss` and `play`; `applicationId io.github.jeshiomurmu.opencaller`; `minSdk 29`, `compileSdk 36`, `targetSdk 36` — written, with unit tests per flavor; not yet built
- [ ] Spotless (ktlint), Detekt, Android Lint configured; `./gradlew check` green — configured; Lint and `check` not yet run (needs CI)
- [x] Ktor server skeleton in `backend/server` with `/health` — `./gradlew check` green locally (2 tests)
- [x] `infra/docker/compose.yml`: postgres, redis, minio, server — all four healthy locally, `curl localhost:8080/health` → 200 (MinIO via a local source build, see Open issues)
- [ ] GitHub Actions `ci.yml`: Android check + assemble both flavors; backend check — written; never run (push blocked)
- [x] Review drafted ADRs 0001 (consent-first data), 0002 (Ktor backend), 0003 (GPL-3.0) in `docs/decisions/` — all remain Accepted; 0002 notes the MinIO change

**Exit criteria:** CI green on `main`; `assembleFossDebug` installs and opens an empty app on an emulator; `curl localhost:8080/health` returns 200.

## Next step

Run **P-01** from `docs/prompts.md`, but only after the Phase 0 blockers below are cleared and the exit criteria are met.

## Open issues / blockers

1. **GitHub push refused (403).** The agent session has read-only access to `jeshiomurmu/OpenCaller`. Fix: connect or reconnect GitHub at https://claude.ai/connect-github and make sure the Claude GitHub App is installed on the repo. Then push `phase/00-tooling`, open the PR and get CI green. Until that happens, the Android build, Lint and both flavors are **unverified**.
2. **`LICENSE` missing.** www.gnu.org is blocked in the agent sandbox. Add the verbatim https://www.gnu.org/licenses/gpl-3.0.txt as `LICENSE` (human task, or allow the host).
3. **Android not built locally.** The agent sandbox cannot reach `dl.google.com` (Android SDK, Google Maven), and there is no KVM for an emulator. The emulator install/launch check (exit criterion) still has to be done on a dev machine or with an emulator job in CI.
4. **MinIO distribution.** MinIO stopped publishing container images, and its GitHub repo is archived. `compose.yml` defaults to `cgr.dev/chainguard/minio:latest` (free tier, unpinned, not testable from the sandbox; its healthcheck assumes `curl` is present), overridable with `MINIO_IMAGE`. Needs a decision: keep Chainguard, build from source, or switch to an S3-compatible alternative (Garage/SeaweedFS) via an ADR.
5. **Lint `OldTargetApi` disabled.** Android 17 (API 37) is out, but `targetSdk 36` is fixed by `architecture.md` §2. Revisit the target SDK before the first Play release (Phase 15).
6. **Detekt 1.23.8 Gradle plugin** triggers Gradle 10/11 deprecation warnings (`ReportingExtension.file`, `Configuration.setVisible`). Detekt 2.0 is still alpha; upgrade when it is stable.
7. **CI test coverage.** The CI command runs only the `app` flavor unit tests. Library and JVM module tests (`testDebugUnitTest`, `test`) must be added to CI when the first ones appear (Phase 1).
8. **Gradle 9.8.1** is used for both builds. AGP 9.4.0 declares Gradle ≥ 9.6.0 (minimum and default 9.6.0).

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
| Compose BOM | 2026.09.00 (UI 1.12.1, Material 3 1.4.0) | https://developer.android.com/develop/ui/compose/bom/bom-mapping, https://developer.android.com/jetpack/androidx/releases/compose-ui | 2026-10-16 |
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
| Docker images | `postgres:18`, `redis:8.8`, `eclipse-temurin:17-jdk-noble` / `17-jre-noble` | https://hub.docker.com (tag API) | 2026-10-16 |

## Progress log (newest first)

| Date | Phase | What changed | Verified by |
| --- | --- | --- | --- |
| 2026-10-16 | 0 | Bootstrapped `android/` (catalog, 7 convention plugins, 35 modules, flavors, Compose MainActivity, Spotless/Detekt/Lint), `backend/server/` (Ktor `/health`, Dockerfile), `infra/docker/compose.yml`, `ci.yml`, CHANGELOG, NOTICE; ADRs reviewed | Backend `./gradlew check` green; compose up, all healthy, `/health` 200; Spotless + Detekt on Android sources green. Android Gradle build, Lint and CI **not run** (see Open issues) |
| 2026-10-15 | — | Planning docs created | Human review |

## Phase history

| Phase | Started | Exited | Notes |
| --- | --- | --- | --- |
| | | | |
