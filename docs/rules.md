# Rules for AI agents (and humans) — OpenCaller

These rules override any default behaviour of the coding agent. When a rule conflicts with a user prompt, stop and ask.

## 1. Workflow

1. **Start every task** by reading `docs/phase.md` and the section of `docs/implementation_plan.md` for the current phase.
2. **Scope:** do only the checklist items of the current phase. If the prompt asks for something from another phase or a PRD non-goal (`docs/prd.md` §8), stop and say so.
3. **Plan first:** before writing code, post a short plan: files to create/change, tests to add, commands to run.
4. **Small steps:** one checklist item at a time; build and test after each.
5. **Finish every task** by: running the verification commands (§6), updating `docs/phase.md` (ticks, log entry, next step, open issues), and committing with a Conventional Commit message (`feat(blocking): add regex rule editor`).
6. **Never mark an item done** if tests fail, the build has warnings in new code, or the feature was not exercised (unit test, Robolectric test or documented manual QA step).

## 2. Never do

- Never upload or transmit contacts, call log entries, SMS bodies, or lists of numbers the user communicated with. (Privacy invariants: `architecture.md` §10.)
- Never add ads, ad SDKs, Firebase Analytics, Crashlytics, or any third-party tracker. FCM is allowed only in the `play` flavor and only for data-less "sync" pushes.
- Never use the Accessibility API for call recording or reading other apps' screens.
- Never use hidden/non-SDK APIs, reflection into framework classes, or root.
- Never block outgoing calls or emergency numbers. Screening errors must **fail open** (allow the call).
- Never hard-code secrets, API keys, server URLs for production, or signing material. Use `BuildConfig` fields fed from Gradle properties / CI secrets.
- Never invent a library, API, method signature, Gradle coordinate, or version number. (§3)
- Never commit generated build output, keystores, `google-services.json`, or `.env` files.
- Never silently swallow exceptions (`catch (e: Exception) {}`). Log with the redacting logger and return an `AppError`.
- Never put business logic in Composables or Activities.
- Never add a dependency between two `feature:*` modules.
- Never write "TODO: implement" stubs and call the task done. If something must wait, record it in `docs/phase.md` → Open issues.

## 3. Libraries and versions (verify, don't guess)

Allowed libraries are listed in `architecture.md` §2. For anything else, ask first.

When adding or updating a dependency:

1. Find the **latest stable** (non-alpha/beta/rc unless the stable doesn't exist and an ADR approves it) on the official source: Maven Central (`search.maven.org`), Google Maven (`maven.google.com`), or the library's official release notes.
2. Add it to `gradle/libs.versions.toml` with the exact version. Never write versions inline in module build files.
3. Check compatibility: Kotlin ↔ KSP ↔ Compose compiler plugin ↔ AGP ↔ Gradle. The AGP release notes list the compatible Gradle version.
4. If you cannot reach the source to verify, **stop and tell the user**, listing what you need verified.
5. For Android APIs, check the API level on developer.android.com and guard with `Build.VERSION.SDK_INT` checks or `@RequiresApi`.

Preferred libraries (do not swap without an ADR):

| Need | Use | Don't use |
| --- | --- | --- |
| DI | Hilt | Koin, manual service locators |
| Networking | Retrofit + OkHttp + kotlinx.serialization | Volley, Gson, Moshi |
| DB | Room + SQLCipher | Realm, raw SQLiteOpenHelper |
| Async | Coroutines/Flow | RxJava, AsyncTask, LiveData in new code |
| Images | Coil | Glide, Picasso |
| Phone numbers | libphonenumber | Hand-written regex parsing |
| ML | LiteRT | Cloud inference for SMS |
| Logging | Timber + RedactingTree | `println`, `Log.d` with raw numbers |
| Secure storage | Android Keystore-wrapped keys + DataStore | Deprecated `androidx.security:security-crypto` |
| Testing | JUnit4, MockK, Turbine, Robolectric, Compose UI test, Testcontainers (server) | Mockito in Kotlin code |

## 4. Code style

- Kotlin official style; formatted by Spotless (ktlint). Detekt with the project config. Zero new warnings.
- Max function length ~40 lines; max file ~400 lines; split otherwise.
- Public functions and classes in `core:*` have KDoc.
- Immutable data classes for state; `sealed interface` for UI events and errors.
- ViewModel pattern: `UiState` (data class), `UiEvent` (sealed), `StateFlow<UiState>` via `stateIn(WhileSubscribed(5_000))`.
- Composables: stateless where possible, take `modifier: Modifier = Modifier` as first optional param, have `@Preview` (light + dark, font scale 2.0 for text-heavy screens).
- Strings in `strings.xml` only (no hard-coded user-facing text). Plurals via `pluralStringResource`.
- Colors, type and spacing only from `core:designsystem` tokens (`design.md`).
- Phone numbers: always `PhoneNumber` value class holding E.164; never pass raw `String` numbers across module boundaries.
- Time: inject `Clock`; never call `System.currentTimeMillis()` in logic.
- Dispatchers: inject `DispatcherProvider`; never hard-code `Dispatchers.IO` in classes.

## 5. Error handling

- Repositories return `AppResult<T>` (`Success` / `Failure(AppError)`); they never throw to callers.
- Map exceptions at the boundary: `IOException` → `AppError.Network`, HTTP 401 → `AppError.Auth`, 429 → `AppError.RateLimited(retryAfter)`, `SQLiteException` → `AppError.Storage`.
- UI maps `AppError` to a string resource and an action (Retry, Sign in again, Open settings).
- Telephony and SMS receivers: wrap the whole pipeline in `runCatching`; on failure allow the call / store the SMS as Personal and log.
- Server: every route uses a shared `StatusPages` mapping to problem+json; never leak stack traces; log with a request ID.
- When a test fails, fix the cause. Do not delete or weaken the test unless the test itself is wrong, and explain why in the commit.
- When you hit an error you can't resolve in 3 attempts, stop, write it into `docs/testing.md` → Bug log with steps tried, and ask the user.

## 6. Verification commands (run before every commit)

```bash
# Android
cd android
./gradlew spotlessCheck detekt lint testFossDebugUnitTest testPlayDebugUnitTest
./gradlew assembleFossDebug assemblePlayDebug

# Backend
cd backend/server
./gradlew check            # unit + Testcontainers integration tests (Docker required)

# Full stack smoke (from repo root)
docker compose -f infra/docker/compose.yml up -d
./scripts/smoke.sh          # created in Phase 4
```

Instrumented tests (`connectedFossDebugAndroidTest`) run on an emulator before merging telephony or SMS changes.

## 7. Security

- TLS only; `play` flavor pins the server's public key (with a backup pin); `foss` uses system trust + optional user pin for self-hosters.
- Access token 15 min, refresh token 30 days rotating, stored encrypted.
- All server inputs validated (E.164 via libphonenumber on server too; length limits; enum whitelists).
- Rate limits in Redis: OTP start 3/hour/number and 10/hour/IP; search 50/day free; reports 100/day.
- Admin endpoints on a separate port, behind auth and IP allowlist.
- Dependency updates reviewed weekly (Renovate or Dependabot) — never auto-merge major versions.

## 8. Git

- Branch per phase: `phase/03-call-screening`; PR into `main`; squash merge.
- Conventional Commits. Scope = module name.
- Tag releases `vMAJOR.MINOR.PATCH`; CHANGELOG.md updated from commits.

## 9. Communication with the user

- Be direct. Report what was done, what was verified (with command output summaries), and what is left.
- If something is impossible on Android or against Play policy, say so plainly and propose the closest compliant option.
- Never claim something works without having run the test or the app.
