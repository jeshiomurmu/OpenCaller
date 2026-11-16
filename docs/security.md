# Security review — OpenCaller

Phase 14 (2026-11-16). The app's and the server's state, control by control, against **OWASP MASVS v2** (the controls
as published in `OWASP/masvs`, `controls/MASVS-*.md`), with the evidence: a test that runs in CI, a CI check, or a
place in the code. "Device step" means something only a phone can show; those steps are in `docs/testing.md`.

Result: **Pass** (meets the control), **Pass with note** (meets it; the residual risk is written down and
accepted or tracked), **Gap** (doesn't meet it yet; tracked as an open issue in `docs/phase.md`), **N/A** (doesn't
apply to this app, with the reason).

Severity of findings follows `docs/testing.md` §6 (S1 critical … S4 cosmetic).

## Summary

| Control | Result | Evidence |
| --- | --- | --- |
| MASVS-STORAGE-1 | Pass | SQLCipher database, Keystore-wrapped keys, encrypted tokens, no backup |
| MASVS-STORAGE-2 | Pass with note | No logs in release, redaction in debug, OTPs hidden on the lock screen and marked sensitive on the clipboard |
| MASVS-CRYPTO-1 | Pass | AES-256-GCM (Keystore), PBKDF2-HMAC-SHA256 600,000 iterations for backups, TLS only |
| MASVS-CRYPTO-2 | Pass with note | Non-exportable Keystore keys; a lost key wipes local data (issue 10) |
| MASVS-AUTH-1 | Pass with note | OTP sign-in, 15-min JWT, rotating refresh tokens with reuse detection; Play Integrity not verified yet (issue 39) |
| MASVS-AUTH-2 | N/A | No local authentication (no PIN or biometric unlock in the app) |
| MASVS-AUTH-3 | Pass with note | Account deletion and business claims need a signed-in session; no step-up authentication |
| MASVS-NETWORK-1 | Pass | HTTPS only in release (network security config), cleartext only to local hosts in debug |
| MASVS-NETWORK-2 | Pass with note | play: public-key pins with a backup (`PinningTest`); foss: system trust by design (self-hosters) |
| MASVS-PLATFORM-1 | Pass | Every exported component guarded or reviewed (`ExportedComponentsTest`), extras validated, explicit mutable PendingIntents |
| MASVS-PLATFORM-2 | N/A | No WebView anywhere in the app |
| MASVS-PLATFORM-3 | Pass with note | OTP warning hides other apps' overlays (Android 12+); no `FLAG_SECURE` (screenshots of spam are a feature) |
| MASVS-CODE-1 | Pass | minSdk 29, targetSdk 36 |
| MASVS-CODE-2 | Gap (S3) | No forced-update mechanism (Play in-app updates not used) |
| MASVS-CODE-3 | Pass | OSV audit of every resolved dependency in CI (`dependency-audit` job), Renovate/Dependabot weekly per rules.md §7 |
| MASVS-CODE-4 | Pass | Validation on both sides; parameterised SQL only (`SqlInjectionTest`) |
| MASVS-RESILIENCE-1…4 | N/A (by design) / Gap for R8 | Open-source app: no root detection or anti-tampering; release minification is off (issue 59) |
| MASVS-PRIVACY-1…4 | Pass | See `docs/architecture.md` §10, the traffic check and `PRIVACY.md` |

## MASVS-STORAGE — data at rest

**STORAGE-1: The app securely stores sensitive data. — Pass**

- The local database (block rules, screening log, spam list, profile cache, recordings index, screened calls) is
  SQLCipher. Its passphrase is random and stored wrapped by an AES-256-GCM key in the Android Keystore
  (`core/database/.../crypto/AndroidKeystoreCipher.kt`, `DatabasePassphraseStore.kt`; Phase 1, verified on the
  API 29 and 36 emulators by `core:database:connectedDebugAndroidTest`).
- Sign-in tokens are encrypted with a Keystore AES-GCM key before they go into DataStore
  (`core/data/.../account/KeystoreTokenStore.kt`); tokens that can't be decrypted are deleted (sign in again).
  The deprecated `androidx.security:security-crypto` is not used (rules.md §3).
- `android:allowBackup="false"`, plus data extraction rules that exclude everything, so Android's cloud backup and
  device-to-device transfer never copy the database or the tokens (`app/src/main/AndroidManifest.xml`,
  `res/xml/data_extraction_rules.xml`, `backup_rules.xml`). The app's own backups (Phase 10) are encrypted with the
  user's passphrase (PBKDF2-HMAC-SHA256, 600,000 iterations, AES-GCM; `BackupTest`).
- Recordings are saved in the app's private files directory only.

**STORAGE-2: The app prevents leakage of sensitive data. — Pass with note**

- Release builds plant no logging tree at all (`OpenCallerApplication`); debug builds log through `RedactingTree`,
  which keeps only a number's last 4 digits (`LogRedactor` in `core:common`, tested). Server logs redact numbers in
  paths too (seen in the `SqlInjectionTest` run: `/v1/numbers/+****5678…`).
- OTP notifications are `VISIBILITY_SECRET`: nothing shows on a secure lock screen (issue 100, TC-OTP-008 on the
  emulator). Copied codes are marked `ClipDescription.EXTRA_IS_SENSITIVE`, so Android 13+ hides them in the clipboard
  preview (`SmartInboxParts.kt`, `OtpCopyReceiver.kt`).
- Note (S4): screens are not `FLAG_SECURE`, so screenshots and the recents thumbnail can show messages and codes.
  Taking screenshots of spam to report or share is a use the product wants; the OTP code itself is the user's own.
  Accepted.

## MASVS-CRYPTO

**CRYPTO-1: Current strong cryptography, used according to best practice. — Pass**

- AES-256-GCM with a fresh IV per message (Keystore); PBKDF2-HMAC-SHA256 with 600,000 iterations for backups
  (Argon2 would need an unverified native library; issue 122); TLS for every request in release.
- Server: OTP codes stored as HMAC with a server pepper; JWT HS256 with a ≥ 32-byte secret (config refuses shorter);
  recording codes stored as HMAC; Web Push `aes128gcm` + VAPID (RFC 8291/8292, tested against Tink).
- No custom cryptography; no MD5/SHA-1 for security.

**CRYPTO-2: Key management according to best practice. — Pass with note**

- Device keys are generated in the Keystore, non-exportable, AES/GCM only.
- No secrets in the source or the APK: server URL, pins, Firebase and donation settings come from Gradle properties
  (rules.md §2, `checkReleaseServerConfig`, `checkPlayReleasePins`), server secrets from the environment, signing
  material from CI secrets.
- Note (S3, issue 10): losing the Keystore key (e.g. a restore to a new phone) makes the database unreadable and it
  is recreated empty. Tracked as issue 10.

## MASVS-AUTH

**AUTH-1: Secure authentication and authorization protocols. — Pass with note**

- Sign-in by SMS one-time code (rate limits 3/hour/number and 10/hour/IP, 5 attempts per code; `OtpAttemptsTest`,
  `RateLimitTest`); access tokens are 15-minute JWTs; refresh tokens rotate on every use and a reused refresh token
  revokes the device's chain (`RefreshRotationTest`). Routes check that the caller owns what it reads or changes
  (ownership cases in the API tests, e.g. recordings, family, claims, app calls).
- Admin API: a bearer token on the internal port only; admin web: separate port, IP allowlist, CSRF tokens and an
  audit log (`AdminRoutesTest`, `AdminWebTest`, including that the public API serves neither the admin pages nor
  the admin jobs).
- Notes: Play Integrity tokens are accepted but not verified (issue 39, S3); access tokens can't be revoked before
  they expire (issue 40, S4); the admin web has one shared account (issue 114, S3, Phase 15).

**AUTH-2: Local authentication. — N/A.** The app has no app lock.

**AUTH-3: Additional authentication for sensitive operations. — Pass with note.** Deleting the account, claiming a
business (code by SMS or voice to the claimed number) and family membership (the member's own consent screen) are
tied to the signed-in number; there is no extra step-up check. Accepted for the beta.

## MASVS-NETWORK

**NETWORK-1: Secure network traffic. — Pass**

- Release network security config: `cleartextTrafficPermitted="false"`, system CAs only, no user CAs
  (`app/src/main/res/xml/network_security_config.xml`). Debug builds allow plain HTTP to `10.0.2.2`, `localhost`
  and `127.0.0.1` only (the local development server).
- Server behind TLS at the operator's proxy (`infra/docker/README.md`); LiveKit media is DTLS-SRTP.
- Note: the SIP trunk leg of the recording line isn't TLS/SRTP yet (issue 148, S2 before production; only the
  operator's own recording line, not the app's API).

**NETWORK-2: Identity pinning for endpoints under the developer's control. — Pass with note**

- play release builds pin the API host's public key plus a backup key (Gradle property `opencaller.apiPins`;
  `checkPlayReleasePins` refuses a play release with a server URL and fewer than two `sha256/` pins). `PinningTest` (core:network, run in CI and locally): the current key passes, any
  other key is refused, the backup takes over after rotation, other hosts (presigned picture links, push
  endpoints) use system trust, and no pins means no pinner.
- foss builds use system trust on purpose: F-Droid users may point the app at their own server (rules.md §7).
- Device step: a wrong pin against a real server (testing.md → Phase 14 device steps).

## MASVS-PLATFORM

**PLATFORM-1: IPC mechanisms used securely. — Pass**

- `ExportedComponentsTest` (app, both flavors, debug and release) lists every exported activity, service, receiver
  and provider of the merged manifest, the libraries' included, and fails unless Android guards it with a system
  permission (`BIND_SCREENING_SERVICE`, `BIND_INCALL_SERVICE`, `BROADCAST_SMS`, `BROADCAST_WAP_PUSH`,
  `SEND_RESPOND_VIA_MESSAGE`, `BIND_NOTIFICATION_LISTENER_SERVICE`, `BIND_JOB_SERVICE`,
  `BIND_TELECOM_CONNECTION_SERVICE`, `DUMP`, FCM's `c2dm.permission.SEND`) or it is on a reviewed list:
  - `MainActivity` (launcher, `DIAL` and `SENDTO` links). Its extras are checked before they open anything: a
    report number must be E.164, a blank `tel:` opens an empty dial pad, `sms:` drafts only prefill the composer
    (`MainViewModelTest.externalRequestsAreCheckedBeforeTheyOpenAnything`). Nothing is sent or blocked without the
    user's tap.
  - UnifiedPush's `MessagingReceiverImpl` and `RaiseToForegroundService` (foss): any distributor app may deliver,
    by the protocol. The connector drops messages without the registration token; `UnifiedPushService` ignores any
    message the connector could not decrypt; a message carries only a random push-item id that the app fetches from
    our server with its own session (ADR 0007).
  - The debug-only `QaCommandReceiver` needs `DUMP` (adb shell) and doesn't exist in release builds (same test).
- All `PendingIntent`s are immutable except two that Android must fill in (the notification's inline reply, SMS
  sent/delivered results); both are explicit intents to our own receivers.
- No content provider is exported; WorkManager's and AndroidX Startup's are not exported.

**PLATFORM-2: WebViews. — N/A.** No WebView in the app (checked by search, 2026-11-16).

**PLATFORM-3: The user interface is used securely. — Pass with note**

- The OTP guard warning hides other apps' overlay windows while it shows (`Window.setHideOverlayWindows`, API 31,
  `HIDE_OVERLAY_WINDOWS`): screen-sharing or "support" apps that scammers ask victims to install can't cover or fake
  it. Android 10–11 don't offer this; there the warning still opens over everything through the full-screen intent.
- Sensitive actions (block, unblock, report, delete account) are explicit taps with the number named
  (accessibility labels, Phase 14).
- Note (S4): no `FLAG_SECURE` (see STORAGE-2).

## MASVS-CODE

**CODE-1: Up-to-date platform version. — Pass.** minSdk 29 (Android 10), targetSdk 36; lint `OldTargetApi` is off
only because targetSdk 36 is fixed by architecture §2 (issue 4, revisit before Play production).

**CODE-2: Enforcing app updates. — Gap (S3).** The app can't make a user update. Proposal for Phase 15: the server
answers requests from versions below a minimum with `426`/`upgrade_required` and the app shows "Update OpenCaller"
(works for Play and F-Droid). Tracked as an open issue.

**CODE-3: Components without known vulnerabilities. — Pass**

- `dependency-audit` CI job: resolves the app's release runtime classpaths (foss and play), the server's runtime
  classpath and the Python services' dependency sets, and asks OSV (`POST /v1/querybatch`) about every package
  (`scripts/ci/dependency_audit.py`). It fails on any finding not listed with a reason in
  `docs/security/osv-ignore.txt`. Results of the first run: `docs/phase.md` log.
- Base images are rebuilt on each build (`postgres:18`, `redis:8.8`, `eclipse-temurin:17-*-noble`, Ubuntu's ffmpeg);
  MinIO's `latest` tag can't be pinned (issue 2).

**CODE-4: Untrusted input validated and sanitized. — Pass**

- Server: every request body has length limits and enum whitelists; numbers go through libphonenumber; problem+json
  without stack traces (`ValidationTest`). All SQL is built by Exposed with bound parameters; the few raw
  statements (latest list versions, the transcode claim, the advisory lock and unlock) are constants or bind `?`. `SqlInjectionTest`
  sends injection payloads to search, sign-in and lookup (refused as input errors, never a 500) and as a display
  name (stored and returned as plain text); the tables are untouched.
- App: incoming SMS, MMS PDUs, push bodies, list files (`SpamListFormatTest`), backups, deep links and intent extras
  are parsed defensively; screening fails open on any error (rules.md §5).
- Uploads: the transcoder runs ffmpeg in a read-only container without capabilities (issue 134); pictures are size-
  and type-checked.

## MASVS-RESILIENCE

**RESILIENCE-1…4 (platform integrity, anti-tampering, anti-static and anti-dynamic analysis). — N/A by design,
one gap.** OpenCaller is GPL-3.0 and built reproducibly for F-Droid; hiding how it works defeats the point, and the
server never trusts the client (rate limits, ownership checks, reporter trust). Root detection is not used (rules.md
§2 forbids root-only features, not rooted users). Gap (S3, issue 59): release builds are not minified; R8 with
tested keep rules is planned before the Play production release (Phase 15). Play Integrity verification
(issue 39) would add a platform-integrity signal for abuse scoring on the server.

## MASVS-PRIVACY

**PRIVACY-1…4. — Pass.** The data inventory is `docs/architecture.md` §10 (13 invariants, each with its test), the
user-facing text is `PRIVACY.md` (draft for legal review), and Phase 14 adds:

- invariant 1 enforced in CI (`scripts/ci/privacy-imports.sh`): `core:network` is a JVM module, and no file that
  reads the contacts, call-log or SMS providers imports the network layer (it replaced a Detekt rule the
  architecture promised but that was never written);
- the e2e traffic check: the e2e emulators record their network traffic and `scripts/e2e/check_traffic.py` checks
  every request's host and body against §10 (see `docs/testing.md`, Privacy);
- the onboarding promise about crash reports corrected (the app has none).

## Server checklist (beyond MASVS)

| Item | Result | Evidence |
| --- | --- | --- |
| Admin endpoints isolated | Pass with note | Jobs on the metrics port (9090, not published by compose), bearer token; admin web on 8081 with IP allowlist + CSRF; the public module serves neither (`AdminWebTest`); per-admin accounts in Phase 15 (issue 114) |
| Rate limits | Pass | Redis: OTP 3/h/number, 10/h/IP; search 50/day; reports 100/day (`RateLimitTest`, `SearchApiTest`, `ReportsApiTest`); proxy headers before production (issue 37) |
| Error responses | Pass | Shared StatusPages → problem+json with a request id, no stack traces (`ValidationTest`) |
| Secrets | Pass | From the environment; config refuses short secrets (`ConfigTest`, `RecordingConfigTest`) |
| SSRF on push endpoints | Pass with note | Endpoint host policy and re-resolution (ADR 0007); a narrow DNS-rebinding window remains (issue 133) |

## Findings of this review

| Id | Finding | Severity | Action |
| --- | --- | --- | --- |
| SEC-1 | Architecture §10 invariant 1 claimed a Detekt rule that didn't exist | S2 (unverified privacy claim) | Fixed: CI check `privacy-imports.sh` + negative test; architecture text corrected |
| SEC-2 | Onboarding promised "crash reports stay off unless you turn them on" for a feature that doesn't exist | S3 | Fixed: text says there are no crash reports; invariant 5 rewritten |
| SEC-3 | The OTP guard could be covered by another app's overlay | S3 | Fixed on Android 12+ (`setHideOverlayWindows`) |
| SEC-4 | No dependency vulnerability audit | S3 | Fixed: `dependency-audit` CI job (OSV) |
| SEC-5 | No test that exported components stay guarded | S3 | Fixed: `ExportedComponentsTest` |
| SEC-6 | No forced update | S3 | Open (Phase 15 proposal above) |
| SEC-7 | Release builds not minified | S3 | Open (issue 59) |
| SEC-8 | Play Integrity token not verified | S3 | Open (issue 39) |
