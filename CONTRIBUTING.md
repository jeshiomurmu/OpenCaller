# Contributing to OpenCaller

Thanks for helping. OpenCaller is a GPL-3.0-or-later Android caller ID, spam blocker, dialer and SMS app with a
self-hostable Kotlin (Ktor) server. Everyone taking part follows the [Code of Conduct](CODE_OF_CONDUCT.md); security
problems go through [SECURITY.md](SECURITY.md), never a public issue.

## Before you start

- Read [`docs/rules.md`](docs/rules.md): the project's rules for humans and AI agents alike (privacy invariants,
  allowed libraries, code style, error handling, verification). Pull requests that break them are not merged.
- Bigger changes: open an issue first and agree on the approach. Changes to architecture, a new dependency, or a
  change of a fixed choice (SDK levels, libraries in `docs/architecture.md` §2) need an ADR in `docs/decisions/`.
- [`docs/phase.md`](docs/phase.md) says what is being worked on and lists the open issues; a good place to look for
  something to do.

## Privacy rules that are never negotiable

- Contacts, call log entries, SMS bodies and lists of numbers a user talked to never leave the phone
  (`docs/architecture.md` §10). CI checks this (`scripts/ci/privacy-imports.sh`, the e2e traffic check).
- No ads, analytics, crash-reporting SDKs or trackers. FCM only in the `play` flavor, only for data-less pushes.
- No Accessibility API for reading other apps, no hidden APIs, no root.
- Screening must fail open: if anything goes wrong, the call rings.

## Development setup

| Part | Needs | Build and test |
| --- | --- | --- |
| Android app (`android/`) | JDK 17, Android SDK (API 36) | `./gradlew spotlessCheck detekt lint testFossDebugUnitTest testPlayDebugUnitTest` then `./gradlew assembleFossDebug assemblePlayDebug` |
| Server (`backend/server/`) | JDK 17, Docker (Testcontainers) | `./gradlew check` |
| Agents (`backend/agents/`, `ml/voice-spoof/`) | Python 3.13 | `ruff check . && pytest` |
| Whole stack | Docker | `cp infra/docker/.env.example infra/docker/.env` (fill in), `docker compose -f infra/docker/compose.yml up -d`, `scripts/smoke.sh` |

Instrumented tests (`./gradlew connectedFossDebugAndroidTest`) need an emulator or a phone; CI runs them on API 29 and
36 for every pull request.

## Making a change

1. Branch from `main`.
2. Keep the change small and focused; one checklist item or one fix per pull request.
3. Every change ships with tests: unit tests (JUnit4, MockK, Turbine), Robolectric or Compose UI tests for Android
   code, Testcontainers tests for the server. Don't weaken or delete a failing test to get green; fix the cause.
4. Code style: Kotlin official style via Spotless (ktlint) and Detekt with zero new warnings; user-facing text only in
   `strings.xml`, with Bengali (`values-bn`) and Hindi (`values-hi`) entries (CI's translation check requires them;
   mark them for review if you are not a native speaker); colours and type only from `core:designsystem`.
5. Dependencies: the exact latest stable version from Maven Central or Google Maven, in
   `android/gradle/libs.versions.toml` (or the server's), with where you verified it in the pull request.
6. Commit messages: [Conventional Commits](https://www.conventionalcommits.org/) with the module as scope, e.g.
   `feat(blocking): add regex rule editor`, `fix(server): …`, `docs(deploy): …`.
7. Open the pull request with the template filled in. CI must be green: lint and unit tests, both flavors, the
   server with Testcontainers, the compose smoke test, emulators API 29 and 36, the end-to-end test on two emulators.

## Translations

Strings live in each module's `src/main/res/values*/strings.xml`. Native speakers of Bengali and Hindi are especially
welcome: the current translations were made without one and are marked NEEDS NATIVE REVIEW (`docs/phase.md` issue
171). A new language needs every string translated, including plurals.

## Reporting bugs and asking for features

Use the issue templates. For bugs: phone model, Android version, app version and flavor, what you did, what happened,
and logs with phone numbers removed (debug builds already mask them).

## License

By contributing you agree that your contribution is licensed under GPL-3.0-or-later, the license of the repository
(`LICENSE`, ADR 0003). Code taken from elsewhere must have a compatible license and keep its notices (`NOTICE.md`).
