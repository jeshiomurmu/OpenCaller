# Architecture — OpenCaller

Version 1.0 · 2026-10-15. Changes to this file need an ADR in `docs/decisions/`.

## 1. System overview

```
┌──────────────────────────── Android phone ─────────────────────────────┐
│  Telecom framework ──► CallScreeningService ──► ScreeningEngine         │
│        │                     (core:telephony)        │                  │
│        ▼                                             ▼                  │
│  InCallService (default dialer) ◄── CallerCard ◄── NumberResolver ──────┼──► Lookup API (bucket)
│                                                     ▲   ▲               │
│  SMS_DELIVER receiver ──► MessagePipeline ──────────┘   │               │
│        (default SMS)       ├ SenderResolver            Room DB          │
│                            ├ OtpExtractor          (spam list, rules,   │
│                            ├ SmsClassifier (LiteRT)  reputation cache)  │
│                            └ OtpSafetyGuard                             │
│  WorkManager: list sync, report upload, backup ─────────────────────────┼──► Lists CDN / Reports API
└─────────────────────────────────────────────────────────────────────────┘
                                   │ HTTPS (TLS 1.3, cert pinning in play flavor)
┌──────────────────────────── Backend (docker-compose) ───────────────────┐
│  Ktor API server ── PostgreSQL ── Redis (rate limits, cache)             │
│      │   └── Scoring jobs (Bayesian + GBM), list builder → MinIO/CDN     │
│      ├── LiveKit server + LiveKit SIP ◄── SIP trunk (telephony provider) │
│      │        └── Python agents: AI assistant, recording, voice scanner  │
│      └── Admin web (review queues: business claims, disputes)            │
└─────────────────────────────────────────────────────────────────────────┘
```

## 2. Technology stack

Versions are **not** pinned here. The agent resolves the latest stable version of each library when it is first added and records it in `android/gradle/libs.versions.toml` (or `backend/server/gradle/libs.versions.toml`). See `rules.md` §3.

### Android

| Concern | Choice | Notes |
| --- | --- | --- |
| Language | Kotlin (K2 compiler) | No Java in new code |
| Build | Gradle Kotlin DSL, version catalog, convention plugins in `build-logic/` | JDK 17+ |
| SDK levels | `minSdk 29`, `compileSdk 36`, `targetSdk 36` | API 29 = `ROLE_CALL_SCREENING`; 36 = Play requirement |
| UI | Jetpack Compose, Material 3, Navigation Compose (type-safe routes) | Single activity + in-call activity + overlay |
| DI | Hilt (KSP) | |
| Async | Coroutines, Flow | No RxJava |
| Local DB | Room (KSP) + SQLCipher (`net.zetetic:sqlcipher-android`) | Key in Android Keystore-wrapped secret |
| Settings | DataStore (Proto) | |
| Background | WorkManager | Sync, uploads, backup, scheduled SMS |
| Network | Retrofit + OkHttp + kotlinx.serialization | Ktor client allowed only if a lib requires it |
| Phone numbers | `com.googlecode.libphonenumber:libphonenumber` + `:geocoder` + `:carrier` | Offline metadata |
| On-device ML | LiteRT (formerly TensorFlow Lite) | SMS classifier `.tflite` shipped in assets |
| Images | Coil (Compose) | Business logos, avatars |
| VoIP | LiveKit Android SDK + `androidx.core:core-telecom` (Jetpack Telecom) | Self-managed calls integrate with system call UI |
| Push | `play`: Firebase Cloud Messaging; `foss`: UnifiedPush | Push carries only "sync now" signals |
| Billing | `play`: Google Play Billing Library; `foss`: none | Server verifies purchase tokens |
| Attestation | `play`: Play Integrity API; `foss`: none (lower reporter trust) | |
| Logging | Timber, with a redacting tree | Numbers logged as `+880****1234` |
| Tests | JUnit4, kotlinx-coroutines-test, Turbine, MockK, Robolectric, Compose UI test, Room testing, Macrobenchmark | |
| Static analysis | Detekt, ktlint (via Spotless), Android Lint | Zero warnings policy on new code |

### Backend

| Concern | Choice |
| --- | --- |
| API server | Kotlin + Ktor server (Netty), kotlinx.serialization |
| DB | PostgreSQL (latest stable major), Exposed (DSL) for queries, Flyway migrations |
| Cache / rate limits | Redis |
| Object storage | MinIO (S3-compatible) for spam-list files, logos, video caller IDs |
| Auth | Phone-number OTP → short-lived JWT access token + rotating refresh token |
| Real-time voice | LiveKit server, LiveKit SIP, LiveKit Agents (Python) |
| Scoring | Kotlin batch job (Bayesian reputation) + Python LightGBM model served offline, scores written back to Postgres |
| Deploy | Docker Compose on one VPS for beta; images on GitHub Container Registry |
| Tests | Ktor `testApplication`, Testcontainers (Postgres, Redis) |

Why Ktor, not Go/Rust (as in the research doc): one language across app and server for a small team. Revisit if lookup p95 exceeds 100 ms server time at scale (ADR 0002).

## 3. Android module structure

```
android/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
├── build-logic/convention/            # plugins: opencaller.android.application, .library, .feature, .compose, .hilt, .room, .jvm.library
├── app/                               # MainActivity, InCallActivity, navigation graph, Application, flavors
│   └── src/{main,foss,play}/
├── core/
│   ├── common/        # DispatcherProvider, AppResult<T>, RedactingTree, Clock
│   ├── model/         # pure Kotlin: PhoneNumber, CallerInfo, SpamVerdict, BlockRule, MessageCategory, OtpInfo…
│   ├── phonenumber/   # PhoneNumberNormalizer (E.164), NumberType, CountryResolver, BucketHasher
│   ├── database/      # OpenCallerDatabase, entities, DAOs, migrations, SQLCipher factory
│   ├── datastore/     # UserSettings proto + serializer
│   ├── network/       # OpenCallerApi (Retrofit), AuthInterceptor, TokenStore, DTOs
│   ├── data/          # repositories: NumberRepository, SpamListRepository, ReportRepository, ProfileRepository, SenderIdRepository…; Workers
│   ├── designsystem/  # OpenCallerTheme, color/typography tokens, base components
│   ├── ui/            # CallerCard, SpamBadge, OtpCard, SourceChip, EmptyState
│   ├── telephony/     # RoleManagerHelper, ScreeningEngine, OpenCallerScreeningService, OverlayController, MmiCodes, SimInfo
│   ├── ml/            # SmsClassifier (LiteRT), OtpExtractor, TransactionParser, LinkChecker
│   └── testing/       # fakes for repositories, TestDispatcherRule, fixtures (numbers, SMS samples)
└── feature/
    ├── onboarding/    # welcome, privacy explainer, role requests, sign-up
    ├── callerid/      # caller card overlay, after-call card, notification-listener caller ID (F-ID-05)
    ├── blocking/      # block settings, rules editor, blocked log
    ├── search/        # number search
    ├── profile/       # my profile, visibility, viewers, searchers, incognito, contact requests
    ├── dialer/        # keypad, T9, SIM picker
    ├── calllog/       # call history
    ├── contacts/      # contacts list/detail/edit
    ├── incall/        # InCallService + InCallActivity UI
    ├── messages/      # conversations, thread, compose, smart inbox, spam folder, scheduled SMS
    ├── otp/           # OTP card, safety guard UI, OTP-burst alerts
    ├── business/      # business profile view, claim flow
    ├── premium/       # supporter plans, entitlements, badges, Gold theme
    ├── social/        # Flash messages
    ├── backup/        # backup/restore
    ├── voip/          # app-to-app calls (LiveKit + core-telecom)
    ├── family/        # family group, alerts
    ├── recording/     # recording-line flow, recordings list
    ├── assistant/     # AI assistant setup (call forwarding), live transcript
    ├── scanner/       # voice-clone scan flow
    └── settings/      # all settings, ghost call, announce caller, themes, about, licenses
```

### Dependency rules (enforced by convention plugins + review)

- `feature:*` may depend on `core:*` only. Never on another feature. Cross-feature navigation goes through routes in `app`.
- `core:model` and `core:common` depend on nothing Android-specific (pure Kotlin/JVM).
- `core:data` is the only module that depends on both `core:database` and `core:network`.
- `core:telephony` depends on `core:data`, `core:phonenumber`, `core:model`; it never calls Retrofit directly.
- ViewModels expose `StateFlow<UiState>`; UI never touches repositories.

### Package names

`applicationId`: `io.github.jeshiomurmu.opencaller` (`.debug` suffix for debug). Kotlin packages: `io.github.jeshiomurmu.opencaller.<module path>`, e.g. `…core.telephony`, `…feature.blocking`.

### Build flavors

| Flavor | Distribution | Differences |
| --- | --- | --- |
| `foss` | F-Droid, GitHub Releases | No Google Play Services: UnifiedPush, no Billing, no Play Integrity, backup to file |
| `play` | Google Play, GitHub Releases | FCM, Play Billing, Play Integrity, Drive backup |

Shared code uses interfaces in `core` (e.g. `PushProvider`, `EntitlementProvider`, `IntegrityProvider`, `BackupDestination`) with flavor-specific implementations bound by Hilt in `app/src/<flavor>`.

## 4. App flows

### 4.1 Incoming call (call screening)

```
Telecom binds OpenCallerScreeningService.onScreenCall(details)
 └─ ScreeningEngine.decide(details)  [hard budget 1,500 ms → fail open = allow]
     1. number = normalize(details.handle)            (null ⇒ hidden)
     2. if contact (ContactsProvider lookup)          → ALLOW, source=Contact
     3. user allow rules                               → ALLOW
     4. user block rules (exact/prefix/regex/country)  → BLOCK
     5. hidden && blockHidden                          → BLOCK
     6. foreign && blockForeign && !allowedCountry     → BLOCK
     7. blockNonContacts && !verifiedBusiness           → BLOCK
     8. on-device spam list hit ≥ user threshold        → BLOCK or SILENCE
     9. reputation cache (Room, TTL 24 h)               → info for card
    10. network bucket lookup (timeout 800 ms)          → info for card, may BLOCK if threshold
    11. STIR/SHAKEN verdict (API 30+) as extra signal
 └─ respondToCall(CallResponse: disallow/reject/silence/skipNotification per decision)
 └─ log ScreeningEvent to Room
 └─ if allowed and not default dialer → OverlayController.showCallerCard()
    if default dialer → InCallActivity shows the card itself
```

### 4.2 Outgoing call

Dialer → `TelecomManager.placeCall` → `InCallService` → in-call UI. `CallScreeningService` also sees outgoing calls; we only annotate (never block outgoing).

### 4.3 After call

`InCallService` `onCallRemoved` (dialer) or call-state end (screening-only mode) → after-call card (notification or activity per Android background-start rules) with actions: save, block, report, not spam, call back, message.

### 4.4 Incoming SMS (default SMS app)

```
SMS_DELIVER broadcast → SmsDeliverReceiver → goAsync() → MessagePipeline (≤ 10 s)
  1. write message to Telephony provider (we are default SMS app, we must store it)
  2. SenderResolver: contact → sender-ID registry → claimed business → OTP signals → self-registered → community → template inference
  3. OtpExtractor: code, expiry, domain-bound origin (@domain #code), app hash, flash-call link
  4. SmsClassifier (on-device): Personal / OTP / Transaction / Bill / Delivery / Promotion / Spam / Phishing
  5. LinkChecker: lookalike + known-bad hash-prefix check
  6. OtpSafetyGuard: if OTP && active call with non-contact → full-screen warning
  7. Save MessageMeta row; post notification (OTP notification hides code on lock screen)
```

MMS: `WAP_PUSH_DELIVER` receiver; download via `SmsManager.downloadMultimediaMessage`.

### 4.5 OTP safety guard

Inputs: OTP message or flash call (missed call ≤ 3 s from a known verification-provider range), current call state (`InCallService` calls or `TelephonyCallback` in screening-only mode), caller contact status. Rule: OTP arrives while a call with a non-contact is active, or within 2 min after one ended → full-screen warning + optional family alert.

### 4.6 Live lookup (k-anonymous bucket)

```
client: h = SHA-256(e164 UTF-8); bucketId = first 16 bits of h (configurable 12–20 per country size)
GET /v1/lookup/buckets/{cc}/{bucketId}  (cc = country calling code)
server: returns all entries in bucket: [{ hash: base64(h_full), info: {...} }]  (cacheable 24 h)
client: match full hash locally; cache bucket in Room
```

The response wraps the entries: `{countryCode, bucket, bits, entries: [{hash, info}]}` (`openapi.yaml`, `BucketResponse`); entries are sorted by hash and only numbers with something to show (spam data, a visible name or a verified business) are included. The server learns country code + bucket (thousands of candidate numbers), never the number. Explicit search (F-ID-03) is different: the user typed the number, the server sees it, it is rate-limited and logged for "who searched me" unless incognito.

App side (Phase 5): `LiveLookupRepository` keeps one row per number in `number_info` for 24 h, including "not found", and records the fetch in `lookup_bucket`; the bucket's other entries are not stored. The request uses its own OkHttp client with an 800 ms call timeout, and the engine gives step 10 its own 800 ms budget inside the 1,500 ms total, so a slow server never makes a call ring late. A network verdict at or above the user's threshold blocks (reason `network`) unless the caller is a contact or a verified business; anything else only adds information to the card. The verdict's source (spam list, earlier lookup, live lookup) is shown in "Why am I seeing this?". `LookupPrivacyTest` (TC-LOOK-002) checks that no request carries the number.

### 4.7 Sign-up

`POST /v1/auth/start {e164}` → server sends OTP (SMS/flash call via provider) → `POST /v1/auth/verify {e164, code, integrityToken?}` → `{accessToken (15 min), refreshToken (30 d, rotating)}`. Tokens are encrypted with an Android Keystore AES-GCM key and stored in DataStore (not the deprecated `security-crypto` library).

Server side: codes are 6 digits, valid 5 minutes, at most 5 wrong attempts, single use; one pending code per number, kept in Redis only as HMAC-SHA256(server pepper, number + code). Refresh tokens are 256-bit random values stored as SHA-256 in `refresh_tokens`; each refresh marks the old row rotated and inserts a new one, and presenting an already-rotated token deletes every refresh token of that device (reuse detection). Access tokens are HS256 JWTs with `sub` = user ID and `did` = device ID.

Names shown in live caller ID: `everyone` profiles appear in bucket results; `registered_only` profiles appear only in buckets fetched with a valid access token; `nobody` never appears.

### 4.8 Reporting

Report saved to `report_outbox` → `ReportUploadWorker` (network constraint, exponential backoff) → `POST /v1/reports` → server stores raw report, scoring job updates `number_reputation`, nightly list builder publishes new per-country list + delta.

App side (Phase 5): reports wait in the outbox until the user is signed in; the worker sends batches of 50, re-schedules itself after a 429's `Retry-After` instead of splitting batches, marks a report the server rejects as `FAILED` and gives up after 10 attempts. Categories are the server's whitelist (`ReportCategory`), shown translated. The spam list comes back through `SpamListSyncWorker` (daily; unmetered networks unless the user allows mobile data; "Update now"): manifest → delta chain or full file → SHA-256 and format checks (§13) → one Room transaction, so a failed update leaves the old list.

Networking (`core:network`, a JVM module): Retrofit + OkHttp + kotlinx.serialization. Requests mark whether they need a token with an `OC-Auth` header that `AuthInterceptor` replaces with the bearer token; `TokenAuthenticator` refreshes on 401 (one refresh at a time; a refused refresh deletes the tokens). The server address comes from BuildConfig (Gradle property `opencaller.apiBaseUrl` for release builds, `http://10.0.2.2:8080/` for debug); without one every request fails at once with `AppError.ServerNotConfigured`. Play release builds pin the server key plus a backup (`opencaller.apiPins`, checked at build time). Failures are logged through `NetworkLog` (the app points it at the redacting logger).

### 4.9 VoIP, recording line, AI assistant, voice scanner

- **VoIP (F-CM-09):** client gets LiveKit token from `/v1/voip/token`; callee notified by push; call registered with Jetpack Telecom so it appears like a phone call.
- **Recording line (F-UT-03):** user taps Record → app dials the user's assigned recording number and merges (conference) → LiveKit SIP room records with beep announcement → agent stores audio in MinIO → app downloads, server deletes.
- **AI assistant (F-UT-04):** onboarding sets conditional call forwarding (no answer / busy / unreachable) to the user's assistant number using standard GSM MMI codes where supported, with carrier-specific guidance. Calls land in LiveKit SIP → Python agent (STT → LLM → TTS) → transcript streamed to app via LiveKit data channel; user actions sent back.
- **Voice scanner (F-PR-13):** same merge flow as recording; agent samples a few seconds and runs the anti-spoofing model; returns probability.

## 5. Permissions and roles

| Role / permission | Why | When requested |
| --- | --- | --- |
| `ROLE_CALL_SCREENING` | Caller ID + blocking with any dialer | Onboarding step 1 |
| `ROLE_DIALER` | Dialer, in-call UI, call log access | Onboarding (optional, recommended) |
| `ROLE_SMS` | SMS app, OTP features | Onboarding (optional) |
| `READ_CONTACTS`, `WRITE_CONTACTS` | Contact check, contacts screen | Onboarding |
| `READ_PHONE_STATE`, `READ_PHONE_NUMBERS` | SIM info, dual SIM | Onboarding |
| `READ_CALL_LOG`, `WRITE_CALL_LOG`, `CALL_PHONE`, `ANSWER_PHONE_CALLS` | Dialer features | Only after holding `ROLE_DIALER` (Play policy) |
| SMS permissions | SMS app | Only after holding `ROLE_SMS` (Play policy) |
| `SYSTEM_ALERT_WINDOW` | Caller card overlay in screening-only mode | When user enables overlay |
| `POST_NOTIFICATIONS` | After-call card, OTP, alerts | Onboarding |
| `USE_FULL_SCREEN_INTENT` | Ghost Call, OTP guard | When feature enabled (dialer apps qualify) |
| Notification listener access | WhatsApp/Telegram caller ID | When feature enabled |
| `RECORD_AUDIO`, `BLUETOOTH_CONNECT` | VoIP | When first VoIP call |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Sync | Install-time |

Never request a permission before the screen that needs it. Every request is preceded by an explainer screen (design.md §7).

## 6. On-device data model (Room, SQLCipher-encrypted)

```kotlin
// core:database — entity summary (columns; types are Kotlin types)

@Entity(tableName = "spam_list", primaryKeys = ["e164"])
SpamListEntry(e164: String, score: Float, category: String, reportCount: Int, listVersion: Long)

@Entity(tableName = "number_info", primaryKeys = ["e164"])
NumberInfoEntity(e164: String, displayName: String?, nameSource: NameSource, spamScore: Float?,
  spamCategory: String?, reportCount: Int, isBusiness: Boolean, businessId: String?, verified: Boolean,
  logoUrl: String?, callReason: String?, lineType: String?, fetchedAt: Long, expiresAt: Long)

@Entity(tableName = "lookup_bucket", primaryKeys = ["countryCode", "bucketId"])
LookupBucketEntity(countryCode: Int, bucketId: Int, fetchedAt: Long, etag: String?)

@Entity(tableName = "block_rule")
BlockRuleEntity(@PrimaryKey(autoGenerate = true) id: Long, type: RuleType /*EXACT,PREFIX,REGEX,COUNTRY*/,
  pattern: String, action: RuleAction /*BLOCK,SILENCE,ALLOW*/, appliesTo: RuleTarget /*CALLS,SMS,BOTH*/,
  createdAt: Long, enabled: Boolean)

@Entity(tableName = "screening_event")
ScreeningEventEntity(@PrimaryKey(autoGenerate = true) id: Long, e164: String?, direction: Direction,
  decision: Decision, reason: String, spamScore: Float?, stirStatus: Int?, latencyMs: Int, at: Long)

@Entity(tableName = "message_meta", primaryKeys = ["providerId"])
MessageMetaEntity(providerId: Long /*Telephony _id*/, threadId: Long, category: MessageCategory,
  senderName: String?, senderSource: NameSource?, otpCode: String?, otpOrigin: String?, otpExpiresAt: Long?,
  riskFlags: Int /*bitmask: PHISHING_LINK, OTP_DURING_CALL, UNREQUESTED_OTP…*/, classifierScore: Float, at: Long)

@Entity(tableName = "sender_id", primaryKeys = ["countryIso", "header"])
SenderIdEntity(countryIso: String, header: String, brandName: String, category: String, source: String,
  businessId: String?, updatedAt: Long)

@Entity(tableName = "otp_app_hash", primaryKeys = ["hash"])
OtpAppHashEntity(hash: String, packageName: String, appName: String)

@Entity(tableName = "verification_range", primaryKeys = ["prefix"])
VerificationRangeEntity(prefix: String /*E.164 prefix used by flash-call providers*/, provider: String)

@Entity(tableName = "report_outbox")
ReportOutboxEntity(@PrimaryKey(autoGenerate = true) id: Long, e164: String, label: ReportLabel /*SPAM,NOT_SPAM*/,
  category: String?, comment: String?, createdAt: Long, attempts: Int, state: SyncState)

@Entity(tableName = "recording")
RecordingEntity(@PrimaryKey id: String, e164: String?, filePath: String, durationMs: Long,
  transcript: String?, summary: String?, createdAt: Long)

@Entity(tableName = "scheduled_sms")
ScheduledSmsEntity(@PrimaryKey(autoGenerate = true) id: Long, to: String, body: String, subId: Int,
  sendAt: Long, workId: String, state: SyncState)

@Entity(tableName = "family_member")
FamilyMemberEntity(@PrimaryKey userId: String, displayName: String, role: FamilyRole, joinedAt: Long)
```

Rules: SMS bodies stay in the Android Telephony provider (we don't duplicate them). Contacts stay in ContactsProvider. Every schema change bumps the DB version with an explicit `Migration` and a migration test.

## 7. Server data model (PostgreSQL, Flyway `V1__init.sql`)

```sql
CREATE TYPE visibility AS ENUM ('everyone', 'registered_only', 'nobody');
CREATE TYPE report_label AS ENUM ('spam', 'not_spam');
CREATE TYPE review_status AS ENUM ('pending', 'approved', 'rejected');

CREATE TABLE users (
  id UUID PRIMARY KEY, e164 TEXT UNIQUE NOT NULL, verified_at TIMESTAMPTZ NOT NULL,
  display_name TEXT, avatar_key TEXT, video_caller_id_key TEXT,
  visibility visibility NOT NULL DEFAULT 'registered_only', incognito BOOLEAN NOT NULL DEFAULT false,
  supporter_tier TEXT NOT NULL DEFAULT 'none', created_at TIMESTAMPTZ NOT NULL DEFAULT now(), deleted_at TIMESTAMPTZ);

CREATE TABLE devices (
  id UUID PRIMARY KEY, user_id UUID REFERENCES users(id) ON DELETE CASCADE, flavor TEXT NOT NULL,
  push_type TEXT, push_token TEXT, integrity_level SMALLINT NOT NULL DEFAULT 0, last_seen TIMESTAMPTZ);

CREATE TABLE refresh_tokens (
  token_hash TEXT PRIMARY KEY, device_id UUID REFERENCES devices(id) ON DELETE CASCADE,
  expires_at TIMESTAMPTZ NOT NULL, rotated_at TIMESTAMPTZ);

CREATE TABLE businesses (
  id UUID PRIMARY KEY, legal_name TEXT NOT NULL, display_name TEXT NOT NULL, logo_key TEXT,
  category TEXT, website TEXT, country_iso CHAR(2) NOT NULL, status review_status NOT NULL DEFAULT 'pending',
  verified_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now());

CREATE TABLE business_numbers (
  e164 TEXT PRIMARY KEY, business_id UUID REFERENCES businesses(id) ON DELETE CASCADE,
  default_call_reason TEXT, claimed_via TEXT NOT NULL, claimed_at TIMESTAMPTZ NOT NULL DEFAULT now());

CREATE TABLE sender_ids (
  country_iso CHAR(2) NOT NULL, header TEXT NOT NULL, brand_name TEXT NOT NULL, category TEXT NOT NULL,
  source TEXT NOT NULL, business_id UUID REFERENCES businesses(id), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (country_iso, header));

CREATE TABLE number_reputation (
  e164 TEXT PRIMARY KEY, country_code SMALLINT NOT NULL, bucket_16 INTEGER NOT NULL,
  spam_score REAL NOT NULL DEFAULT 0, category TEXT, report_count INTEGER NOT NULL DEFAULT 0,
  not_spam_count INTEGER NOT NULL DEFAULT 0, line_type TEXT, first_seen TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_report_at TIMESTAMPTZ, model_version TEXT, updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX ON number_reputation (country_code, bucket_16);

CREATE TABLE reports (
  id BIGSERIAL PRIMARY KEY, e164 TEXT NOT NULL, reporter_id UUID REFERENCES users(id) ON DELETE SET NULL,
  label report_label NOT NULL, category TEXT, comment TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX ON reports (e164, created_at);

CREATE TABLE reporter_trust (user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  trust REAL NOT NULL DEFAULT 0.5, updated_at TIMESTAMPTZ NOT NULL DEFAULT now());

CREATE TABLE honeypot_hits (id BIGSERIAL PRIMARY KEY, e164 TEXT NOT NULL, honeypot TEXT NOT NULL, at TIMESTAMPTZ NOT NULL);

CREATE TABLE disputes (
  id BIGSERIAL PRIMARY KEY, e164 TEXT NOT NULL, claimant_user_id UUID REFERENCES users(id),
  reason TEXT NOT NULL, status review_status NOT NULL DEFAULT 'pending', reviewer TEXT, resolved_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now());

CREATE TABLE unlist_requests (
  id BIGSERIAL PRIMARY KEY, e164 TEXT NOT NULL, verified_at TIMESTAMPTZ, processed_at TIMESTAMPTZ);

CREATE TABLE profile_views (
  id BIGSERIAL PRIMARY KEY, viewed_user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  viewer_user_id UUID REFERENCES users(id) ON DELETE CASCADE, kind TEXT NOT NULL /* view | search */,
  at TIMESTAMPTZ NOT NULL DEFAULT now());

CREATE TABLE contact_requests (
  id BIGSERIAL PRIMARY KEY, requester_id UUID REFERENCES users(id) ON DELETE CASCADE,
  target_id UUID REFERENCES users(id) ON DELETE CASCADE, status review_status NOT NULL DEFAULT 'pending',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now());

CREATE TABLE subscriptions (
  user_id UUID REFERENCES users(id) ON DELETE CASCADE, platform TEXT NOT NULL, product_id TEXT NOT NULL,
  purchase_token_hash TEXT NOT NULL, status TEXT NOT NULL, expires_at TIMESTAMPTZ, PRIMARY KEY (user_id, platform));

CREATE TABLE family_groups (id UUID PRIMARY KEY, owner_id UUID REFERENCES users(id) ON DELETE CASCADE);
CREATE TABLE family_members (group_id UUID REFERENCES family_groups(id) ON DELETE CASCADE,
  user_id UUID REFERENCES users(id) ON DELETE CASCADE, role TEXT NOT NULL, consented_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (group_id, user_id));

CREATE TABLE list_versions (country_iso CHAR(2), version BIGINT, kind TEXT /* full | delta | extended */,
  object_key TEXT NOT NULL, sha256 TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (country_iso, version, kind));
```

Data retention: raw reports 18 months then aggregated; profile views 90 days; recordings on server ≤ 1 h; deleted accounts purged ≤ 30 days.

Later migrations (V1 stays exactly the SQL above): `V2__jobs_and_lookup_indexes.sql` adds `job_state (name TEXT PRIMARY KEY, watermark TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now())` for the scoring job's progress and indexes on `reports (created_at)`, `number_reputation (updated_at)`, `number_reputation (spam_score)`, `unlist_requests (e164)`, `disputes (e164, status)` and `profile_views (viewed_user_id, at)`.

`number_reputation` doubles as the lookup index: every signed-up user and business number gets a row (no reports yet) so bucket lookups can find names; the scoring job adds any that are missing.

## 8. API (v1, JSON over HTTPS)

| Method & path | Auth | Purpose |
| --- | --- | --- |
| `POST /v1/auth/start` | none | Send OTP to number (rate-limited per number and IP) |
| `POST /v1/auth/verify` | none | Verify OTP → tokens |
| `POST /v1/auth/refresh` | refresh | Rotate tokens |
| `GET /v1/lists/{countryIso}/manifest` | optional | Latest full/delta/extended list versions + URLs + sha256 |
| `GET /v1/lists/{countryIso}/files/{version}/{full|delta}` | none | List file (§13), immutable |
| `GET /v1/lookup/buckets/{cc}/{bucketId}` | optional | k-anonymous bucket (§4.6); `ETag` support |
| `GET /v1/numbers/{e164}` | access | Explicit search; quota; records "searched" unless incognito |
| `POST /v1/reports` | access | Batch of reports |
| `GET /v1/sender-ids/{countryIso}?since=` | none | Sender-ID registry delta |
| `GET /v1/otp-sources?since=` | none | App-hash table + verification-provider ranges |
| `GET/PUT /v1/me` | access | Profile, visibility, incognito |
| `DELETE /v1/me` | access | Delete account |
| `GET /v1/me/viewers`, `GET /v1/me/searchers` | access + supporter | F-SO-02/03 |
| `POST /v1/contact-requests`, `POST /v1/contact-requests/{id}/{approve|deny}` | access | F-SO-05 |
| `POST /v1/business/claims`, `GET /v1/business/{id}` | access / none | F-BZ-01/02 |
| `POST /v1/disputes`, `POST /v1/unlist/start`, `POST /v1/unlist/verify` | access / none | F-PR-16/17 |
| `GET /unlist`, `POST /unlist`, `POST /unlist/verify` | none | Unlist web form (server-rendered HTML, no app needed) |
| `POST /v1/billing/verify` | access | Play purchase token verification |
| `POST /v1/family`, `POST /v1/family/{id}/invite`, `POST /v1/family/alerts` | access | F-PR-14 |
| `POST /v1/voip/token` | access | LiveKit token |
| `POST /v1/lines/{recording|assistant|scanner}` | access + entitlement | Allocate/return line number and room |
| `POST /v1/media/video-caller-id` | access | Upload (pre-signed URL) |

Errors: RFC 9457 problem+json `{type, title, status, detail, code}`. Every endpoint has an OpenAPI description in `backend/server/src/main/resources/openapi.yaml`.

## 9. Spam scoring (server)

Bayesian reputation per number (see research doc §Core algorithms):

```
P(spam | n) = (α0 + Σ wᵢ·e^(−λ·tᵢ)·sᵢ) / (α0 + β0 + Σ wᵢ·e^(−λ·tᵢ))
```

`sᵢ` 1 for spam, 0 for not-spam; `wᵢ` reporter trust; `tᵢ` report age; half-life 30 days; priors by number range and line type. Blended with a LightGBM behaviour model (honeypot hits, report bursts, distinct reporters, STIR failure rate reported by clients as aggregate counters). Thresholds: `≥ 0.85` = Likely spam, `0.6–0.85` = Suspicious. Verified businesses are never auto-labelled spam; they go to human review.

Implementation (Phase 4, `scoring/`): the Kotlin job runs every 15 minutes under a PostgreSQL advisory lock (one server instance at a time) and rescores numbers with reports newer than its watermark plus numbers not rescored for a day (so decay shows). Defaults, all configurable by environment variable: α0 = 1, β0 = 2 (prior mean 1/3), half-life 30 days, default reporter trust 0.5. Only each reporter's latest report on a number counts. A verified business number (business `approved`) is capped at 0.39, below every threshold the apps use (the app's lowest is 0.4), and one pending dispute is opened for human review. The same run processes verified unlist requests. Priors by number range and line type wait for line-type data (Phase 9); the LightGBM blend is a later phase.

## 10. Privacy invariants (tested in CI where possible)

1. No API accepts contact lists, call logs or SMS bodies. A lint check fails the build if `ContactsContract`, `CallLog` or `Telephony.Sms` cursor data flows into a class in `core:network` (Detekt custom rule).
2. Live caller ID never sends the full number; only explicit search does.
3. Logs redact numbers to last 4 digits.
4. Reports send the reported number, label, category and optional comment; no device identifiers beyond the auth token.
5. Crash reporting is opt-in and goes to a self-hosted endpoint (or none).

## 11. Error handling

- Domain errors as `sealed interface AppError` (Network, Auth, RateLimited, NotFound, Validation, ServerNotConfigured, Storage, Unexpected) wrapped in `AppResult<T>`.
- Telephony paths fail open: any exception in screening → allow the call, log `ScreeningEvent(reason="error")`.
- Workers retry with exponential backoff; give up after 10 attempts and surface in Settings → Sync status.
- UI shows actionable messages (design.md §8). Never show stack traces.

## 12. Observability

Server: structured JSON logs, Prometheus metrics endpoint (request latency, lookup p95, scoring lag), Grafana dashboards in `infra/docker`. App: local debug screen (Settings → About → tap version 7×) with screening latency histogram and last 100 decisions.

## 13. List file format

Per-country spam lists (`GET /v1/lists/{countryIso}/manifest`) are binary files built nightly by the server (`lists/ListFormat.kt`, `lists/ListBuilder.kt`) and stored in MinIO as `lists/{ISO}/{version}/full.ocsl` and `lists/{ISO}/{version}/delta.ocsl`. A number is listed when its spam score is at least 0.4 (`LIST_MIN_SCORE`) and it has spam reports; its country is libphonenumber's region for it (non-geographic numbers such as +800 are not listed). A country gets a new version only when its list changed.

All integers are big-endian and unsigned.

| Field | Size (bytes) | Meaning |
| --- | --- | --- |
| magic | 4 | ASCII `OCSL` |
| format | 1 | Format revision, `1` |
| kind | 1 | `1` = full list, `2` = delta |
| country | 2 | ISO 3166-1 alpha-2, ASCII upper case (e.g. `BD`) |
| version | 8 | List version, starting at 1 and increasing by 1 per published change |
| baseVersion | 8 | Delta: the version it applies to (`version − 1`); full: `0` |
| createdAt | 8 | Build time, seconds since the Unix epoch (UTC) |
| categoryCount | 1 | Number of category names that follow (0–254) |
| categories | variable | `categoryCount` × (length u8, 1–255, then UTF-8 bytes), sorted; entries refer to them by index |
| entryCount | 4 | Number of entries |
| removalCount | 4 | Number of removals (always 0 in a full list) |
| entries | 14 × entryCount | Sorted by number, strictly ascending (see below) |
| removals | 8 × removalCount | Numbers to delete, sorted strictly ascending (delta only) |
| sha256 | 32 | SHA-256 of every byte before it |

Entry (14 bytes):

| Field | Size | Meaning |
| --- | --- | --- |
| number | 8 | The E.164 number without `+`, as an integer (`+8801712345678` → 8801712345678; at most 15 digits) |
| score | 1 | Spam score in whole percent, 0–100, rounded down (0.849 → 84) |
| category | 1 | Index into `categories`, or `0xFF` for none |
| reportCount | 4 | Spam reports (one per reporter) |

Applying a delta: start from version `baseVersion`, insert or replace every entry (by number), delete every removal; the result is exactly the full list of `version`. The manifest lists the latest full file and the deltas of the last 14 versions with each file's SHA-256 (of the whole file, trailer included), so an app checks the download against the manifest and the file against its own trailer. An app more than 14 versions behind, or holding no list, downloads the full file.

Example (full list, BD, version 3, built 2026-10-16T12:00:00Z, entries +12015550123 at 91 % "scam" with 214 reports and +8801712345678 at 62 % with 7 reports; 106 bytes, `ListFormatTest`):

```
4f43534c 01 01 4244 0000000000000003 0000000000000000 000000006ad211c0
01 04 7363616d
00000002 00000000
00000002cc2ebeab 5b 00 000000d6
000008014ef0124e 3e ff 00000007
0447feb7d1a5f7f6e779d9dda92dfea006ae9807cc917f136402a045dba1382d
```
