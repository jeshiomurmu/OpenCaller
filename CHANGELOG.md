# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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
