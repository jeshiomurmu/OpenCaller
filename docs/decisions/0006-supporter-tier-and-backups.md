# ADR 0006 — Supporter tier, entitlements, the extended list and encrypted backups

- Status: Accepted
- Date: 2026-11-03
- Phase 10: supporter subscription, self-hosted unlock, extended spam list, Ghost Call, Announce Caller, backup,
  themes, messenger caller ID (`docs/implementation_plan.md`)

## Context

The supporter tier pays for the service without ads or trackers (`docs/prd.md` §6.5). The play build sells it through
Google Play. The foss build has no Google Play Services and is often used against a self-hosted server. Owner
decisions of 2026-11-03:

- one subscription with the base plans `supporter`, `gold` and `family`;
- the extended spam list is the part of a country's list beyond a size cap;
- Ghost Call, Announce Caller, creating a backup, messenger caller ID, the extended list and the Gold theme need an
  entitlement;
- Drive and file backups are both encrypted with the user's passphrase.

## Decision

### The server decides; the phone only asks

An entitlement is the server's answer (`GET /v1/entitlements`). The app never unlocks anything from a local purchase
alone.

- **play:** Play Billing 9.1.0 (callback API) shows the base plans with Play's prices and starts the purchase with
  an obfuscated account ID. The ID is HMAC-SHA256 of the user ID with `BILLING_ACCOUNT_SECRET`, so Google never sees
  our user IDs. The app sends the purchase token to `POST /v1/billing/verify`. The server reads it with the Google
  Play Developer API (`purchases.subscriptionsv2.get`, service account from `GOOGLE_PLAY_SERVICE_ACCOUNT_FILE`) and
  checks the package, the product and the account ID. It refuses a token bound to another account. Then it
  acknowledges the purchase and stores it.
- **Access by state:** active and in-grace subscriptions give access until expiry plus 2 hours. Cancelled ones give
  access until their paid period ends. Any other state (on hold, paused, expired, revoked) gives none.
- **Re-check:** an hourly job re-reads stored purchases from Google, so renewals, refunds and cancellations take
  effect without the app. Real-time developer notifications (Pub/Sub) are not used yet (see Consequences).
- **Restoring purchases:** a purchase made on another phone, or before a reinstall, is found in the store account.
  It is re-sent on the next refresh.
- **foss and self-hosting:** a server started with `SELF_HOSTED=true` reports it in `GET /v1/server`. Every signed-in
  or anonymous user of that server is then entitled, without a badge. Otherwise the foss build offers a donation link
  (`opencaller.donationUrl`) and nothing to buy.
- **Offline:** the phone keeps the last answer in a small DataStore in the no-backup directory, so a backup never
  carries an entitlement to another phone. A purchase holds until its paid period ends plus 7 days. A granted tier or
  a self-hosted server holds for 7 days after the last answer. Without a server nothing is unlocked.
- **Family:** the `family` base plan is sold and gives the holder the supporter benefits. Sharing it with members
  belongs to the family feature (Phase 12, F-PR-14).

### Extended spam list

The standard list keeps a country's most-reported numbers up to `LIST_STANDARD_MAX_ENTRIES` (default 200,000), ranked
by report count, then score. The rest goes into a third file per version, `extended`. With the default cap, today's
lists don't change. The manifest names the file. The server sends it only to an entitled user, or to anyone when
self-hosted, with private caching. The phone stores it in its own table (`spam_list_extended`, Room v4). It downloads
it after the standard list and consults it only while entitled. Without an entitlement it is dropped.

### Backups

A backup holds settings, block rules, the screening log and message categories. It never holds message bodies, call
log or contacts, which stay in Android's own providers. It never holds one-time codes or the entitlement. A message's
category is found again on the new phone by the message's date and address.

- **Format:** gzipped JSON in an envelope: `OCBK`, a format byte, PBKDF2 iterations, a 16-byte salt and a 12-byte
  nonce, then AES-256-GCM ciphertext with the header as associated data. The key comes from PBKDF2-HMAC-SHA256 with
  600,000 iterations. It uses only the platform's `javax.crypto` (Android API 26+), so no library is added. Argon2
  would need a native library that hasn't been verified for this project.
- **Destinations:** a file the user picks through the Storage Access Framework (both builds). The play build adds
  Google Drive's hidden app folder. Authorization uses Google Identity Services' `AuthorizationClient` with the
  `drive.appdata` scope only; no sign-in is needed. Drive v3 REST runs over OkHttp, with no Drive SDK.
- **Encryption:** the file is encrypted on the phone before it leaves, for Drive too. Google stores only ciphertext.
  A forgotten passphrase can't be recovered.
- **Gating:** making a backup needs an entitlement. Restoring never does, so nobody's data is locked behind a lapsed
  subscription.
- **Restore behaviour:** a restore replaces the rules and the log and keeps this phone's onboarding state.

### Utilities

- **Ghost Call:** a WorkManager job waits the chosen delay. It then posts a full-screen call notification that opens
  OpenCaller's own fake incoming-call screen. It never touches Telecom: no call is placed or registered, and nothing
  appears in the call log.
- **Announce Caller:** speaks with TextToSpeech in the phone's language. It speaks only for calls that ring, only when
  the ringer is on and Do Not Disturb isn't filtering, and, in "headphones only" mode, only with headphones connected.
- **Messenger caller ID:** a `NotificationListenerService` reads only the call notifications of WhatsApp, Telegram and
  Signal. It looks up a number only if one is written in the notification text, and only on the phone (never the
  network). Without a number it shows nothing.

## Consequences

- **Setup the owner must do:** create the Play Console product (`opencaller_supporter`, base plans `supporter`,
  `gold`, `family`) and a Google Cloud service account with access to the Play Developer API. Set the server's
  environment (`GOOGLE_PLAY_*`, `BILLING_ACCOUNT_SECRET`). For Drive, register an OAuth client for the play signing
  key.
- **Re-check delay:** without real-time developer notifications, a refund or chargeback takes up to an hour to remove
  access. Add RTDN when the hourly job is not enough.
- **Version coupling:** the extended file belongs to one list version and is replaced whole with each new version. A
  failed download keeps the previous one, so for a day the two files can be from different versions (a number moved
  between them may be in both or, briefly, in neither).
- **Passphrase loss:** a lost passphrase means a lost backup. The screen says so.
- **Ghost Call timing:** WorkManager can ring late in Doze. Exact alarms need a permission Play reserves for clocks
  and calendars.
