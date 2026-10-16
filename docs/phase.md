# Current phase — OpenCaller

> Agents: read this first. Update it at the end of every task (tick items, add a log line, set "Next step", list open issues). Keep the log newest-first and under ~60 lines; move older entries to `docs/phase-history.md`.

## Status

| Field | Value |
| --- | --- |
| **Current phase** | **0 — Repo, tooling, CI** |
| Phase started | — |
| Target exit date | — |
| Branch | `phase/00-tooling` |
| Last green CI run | — |
| App version | 0.0.0 |

## Current phase checklist

Copied from `docs/implementation_plan.md` → Phase 0. Tick here as you go; copy the ticked list back to the plan when the phase exits.

- [ ] `git init`, `main` branch, push to GitHub `jeshiomurmu/opencaller` (private until v0.1)
- [ ] Add `LICENSE` (GPL-3.0 full text from gnu.org) and `CHANGELOG.md`
- [ ] Create Android Gradle project in `android/` with version catalog and `build-logic` convention plugins
- [ ] Create empty modules from `architecture.md` §3 and wire them in `settings.gradle.kts`
- [ ] Flavors `foss` and `play`; `applicationId io.github.jeshiomurmu.opencaller`; `minSdk 29`, `compileSdk 36`, `targetSdk 36`
- [ ] Spotless (ktlint), Detekt, Android Lint configured; `./gradlew check` green
- [ ] Ktor server skeleton in `backend/server` with `/health`
- [ ] `infra/docker/compose.yml`: postgres, redis, minio, server
- [ ] GitHub Actions `ci.yml`: Android check + assemble both flavors; backend check
- [ ] Review drafted ADRs 0001 (consent-first data), 0002 (Ktor backend), 0003 (GPL-3.0) in `docs/decisions/`

**Exit criteria:** CI green on `main`; `assembleFossDebug` installs and opens an empty app on an emulator; `curl localhost:8080/health` returns 200.

## Next step

Run prompt **P-00** from `docs/prompts.md`.

## Open issues / blockers

_None yet._

## Verified dependency versions

Fill as dependencies are added (the source of truth is `libs.versions.toml`; this table records *where* each version was verified and when).

| Library | Version | Verified at (URL) | Date |
| --- | --- | --- | --- |
| | | | |

## Progress log (newest first)

| Date | Phase | What changed | Verified by |
| --- | --- | --- | --- |
| 2026-10-15 | — | Planning docs created | Human review |

## Phase history

| Phase | Started | Exited | Notes |
| --- | --- | --- | --- |
| | | | |
