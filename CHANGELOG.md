# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Android Gradle project (`android/`) with a version catalog, `build-logic` convention plugins
  (`opencaller.android.application`, `.library`, `.feature`, `.compose`, `.hilt`, `.room`, `opencaller.jvm.library`)
  and every `core:*` and `feature:*` module from `docs/architecture.md` §3 as an empty module.
- `app` module with `foss` and `play` flavors (dimension `distribution`), `minSdk 29`, `compileSdk 36`,
  `targetSdk 36`, and a single Compose `MainActivity`.
- Spotless (ktlint), Detekt and Android Lint (warnings as errors, per-module baseline) for the Android project.
- Ktor server (`backend/server/`) with `GET /health`, tests, Spotless, Detekt and a multi-stage, non-root Dockerfile.
- Local development stack in `infra/docker/compose.yml`: PostgreSQL, Redis, MinIO and the server, with healthchecks.
- GitHub Actions CI (`.github/workflows/ci.yml`) for the Android and backend builds.
- `CHANGELOG.md` and `NOTICE.md`.
