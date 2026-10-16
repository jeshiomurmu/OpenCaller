# Product Requirements Document — OpenCaller (Android)

Version 1.0 · 2026-10-15 · Owner: Jeshio Murmu

## 1. Summary

OpenCaller is an open-source Android app that tells you who is calling or texting, blocks spam and scams, and replaces your dialer and SMS app. It matches every Truecaller feature (free and Premium) but builds its identity data only from consent: people who register their own number, verified businesses, and official sender registries. It never uploads anyone's contacts.

## 2. Problem

- Unknown calls and SMS are dominated by spam and fraud. Truecaller reports identifying over 68 billion spam and fraud calls in 2025.
- The dominant solution builds its database from users' address books, which several regulators and a Nigerian court have challenged.
- No open-source app today combines caller ID, a shared spam database, a dialer, an SMS app, OTP protection and AI call screening.

## 3. Target users

| Persona | Need | Primary features |
| --- | --- | --- |
| **Rahim, 34, small-business owner, Dhaka** | Answers many unknown calls; must not miss customers, must avoid loan/fraud calls | Caller ID, spam labels, business claim |
| **Ama, 62, retired teacher, Accra** | Target of OTP and "bank KYC" scams | OTP safety guard, family protection, scam auto-block |
| **Priya, 27, developer, Bengaluru** | Wants privacy and control; distrusts Truecaller | Self-hosting, regex rules, F-Droid build |
| **Shop owner / brand** | Wants calls answered and SMS trusted | Verified business profile, logo, call reason, sender ID |

## 4. Goals and success metrics

| Goal | Metric | Target at v1.0 |
| --- | --- | --- |
| Block spam without blocking real people | Wrong-block rate (user marks blocked call "not spam") | < 1% of blocks |
| Fast caller ID | Time from ring to caller card | p95 < 500 ms (on-device hit), < 1.5 s (network lookup) |
| Protect OTPs | OTP-during-call warning shown | 100% of qualifying events in QA matrix |
| Respect privacy | Bytes of contact/SMS/call-log data sent to server | 0 |
| Stability | Crash-free sessions | ≥ 99.5% |
| Battery | Background drain attributable to app | < 1% per day on reference device |

## 5. Principles (tie-breakers for every decision)

1. **Consent before coverage.** A blank name is better than a harvested one.
2. **On-device first.** Decide locally whenever possible; ask the server only with privacy-preserving queries.
3. **Fail open for calls from real people.** If screening errors or times out, let the call ring.
4. **Explain every label.** Every name and spam label shows its source ("Verified business", "214 reports").
5. **Open and self-hostable.** Every server feature runs from `infra/docker`.

## 6. Feature requirements

Priority: **P0** = MVP (v0.x public beta), **P1** = v1.0, **P2** = after v1.0. "TC equivalent" = Truecaller name for the feature.

### 6.1 Identify

| ID | Feature | TC equivalent | Pri | Acceptance criteria |
| --- | --- | --- | --- | --- |
| F-ID-01 | Live caller ID card on incoming calls | Caller ID | P0 | Shows name/label, source, spam score, country/carrier type within targets in §4; works with app as call-screening app (any dialer) and as default dialer |
| F-ID-02 | After-call card | After-call screen | P0 | Shows who called, duration, actions: save, block, report, "not spam", call back, message |
| F-ID-03 | Number search | Search | P0 | Verified users search any number; rate-limited (default 50/day free); result shows only consented or business data |
| F-ID-04 | Verified business caller ID | Verified Business / Truecaller for Business | P0 | Verified badge, name, logo, call reason; business data cached ≤ 24 h |
| F-ID-05 | Caller ID for WhatsApp / Telegram / Signal calls | Caller ID for WhatsApp | P1 | Parses incoming-call notifications via NotificationListenerService; labels number if present in notification |
| F-ID-06 | Video caller ID | Video Caller ID | P2 | Registered user records ≤ 10 s clip; plays on callee's in-call screen when both are users and callee enabled it |
| F-ID-07 | Unknown-number facts | — | P0 | For numbers with no data: country, mobile/landline/VoIP, past calls from it, "Not a business" |
| F-ID-08 | SMS sender identification incl. OTP sender | Smart SMS / Message ID | P0 | Resolves sender via registered header, domain-bound code, app hash, template, number pool; shows brand + source |
| F-ID-09 | Name source transparency | — | P0 | Every displayed name shows one of: Contact, Self-registered, Verified business, Sender registry, Community label |

### 6.2 Protect

| ID | Feature | TC equivalent | Pri | Acceptance criteria |
| --- | --- | --- | --- | --- |
| F-PR-01 | Community spam list on device | Spam list | P0 | Per-country list downloaded, delta-updated daily; lookups offline |
| F-PR-02 | Auto-block top spammers | Auto-block top spammers | P0 | User picks threshold (high/medium); blocked calls logged with reason |
| F-PR-03 | Extended spam list | Extended top spammers (Premium) | P1 | Optional larger list incl. low-volume spammers |
| F-PR-04 | Block hidden numbers | Block hidden numbers | P0 | Calls with no caller number are rejected when enabled |
| F-PR-05 | Block foreign numbers | Block foreign numbers | P0 | Calls whose country ≠ SIM country rejected when enabled; per-country allow list |
| F-PR-06 | Block non-contacts | Block non-contacts | P0 | Only contacts + verified businesses ring when enabled |
| F-PR-07 | Custom rules: exact, prefix, regex, country | Manual block / series block | P0 | Rules tested in UI before saving; allow rules beat block rules |
| F-PR-08 | Report spam / not spam with category | Report spam | P0 | Report queued offline, synced; category list per §8 of architecture |
| F-PR-09 | STIR/SHAKEN verdict | — | P0 | "Verified by carrier" / "Possibly spoofed" shown when Android provides it (API 30+) |
| F-PR-10 | SMS spam and phishing filter | Spam SMS filter | P0 | On-device classifier; spam folder; link checker flags lookalike domains |
| F-PR-11 | OTP safety guard | — (beyond TC) | P0 | Full-screen warning when an OTP or flash call arrives during a call with a non-contact |
| F-PR-12 | Unrequested-OTP and OTP-burst alerts | — | P1 | Alert on ≥ 3 OTPs from ≥ 2 brands in 10 min |
| F-PR-13 | AI voice-clone scanner | AI Call Scanner (Premium) | P2 | User taps "Scan voice"; call merged with scanner line; result "Likely human / Likely AI" shown as warning, never verdict |
| F-PR-14 | Family protection | Family Protection | P1 | Up to 5 members; admin sees blocked-scam summary, gets alert on OTP-during-call for a member; member consents explicitly |
| F-PR-15 | Scam feed / alerts | Scam Feed | P2 | Curated scam patterns per country from server, shown as cards |
| F-PR-16 | Dispute a label | — | P0 | Number owner verifies ownership by OTP and files dispute; reviewed by humans |
| F-PR-17 | Unlist my number | Unlist | P0 | Anyone (no app needed) can unlist via web form + OTP; processed within 24 h |

### 6.3 Communicate

| ID | Feature | TC equivalent | Pri | Acceptance criteria |
| --- | --- | --- | --- | --- |
| F-CM-01 | Dialer | Dialer | P0 | Default dialer role; T9 search over contacts and log; dual-SIM picker; emergency calls never blocked |
| F-CM-02 | In-call screen | In-call UI | P0 | InCallService UI: mute, speaker, keypad, hold, add/merge call, caller card, Bluetooth audio |
| F-CM-03 | Call log | Call history | P0 | Grouped, filterable (missed, blocked, spam), search, delete |
| F-CM-04 | Contacts | Contacts | P0 | Reads/writes Android ContactsProvider; favourites; no server sync |
| F-CM-05 | SMS/MMS app | Messaging | P0 | Default SMS role; send/receive SMS, MMS, group MMS; dual-SIM; delivery reports |
| F-CM-06 | Smart inbox | Smart SMS / Inbox Cleaner | P0 | Tabs: Personal, OTP, Transactions, Bills, Deliveries, Promotions, Spam; bulk clean old promotions |
| F-CM-07 | OTP card | OTP detection | P0 | Code extracted, copy button, hidden on lock screen, auto-delete option |
| F-CM-08 | Transaction parsing | Smart SMS | P1 | Amount, merchant, account last-4 parsed for top banks per launch country |
| F-CM-09 | Free VoIP calls | Truecaller Voice | P1 | App-to-app audio calls over Wi-Fi/data via self-hosted LiveKit; works through NAT (TURN) |
| F-CM-10 | Flash messages | Flash | P2 | One-tap status pings ("Call me", "On my way") with optional location, app-to-app |
| F-CM-11 | Scheduled SMS | Schedule messages | P1 | WorkManager-scheduled send with exact time option |
| F-CM-12 | Quick reply / message from after-call card | Quick reply | P0 | Templates editable |

### 6.4 Profile and social

| ID | Feature | TC equivalent | Pri | Acceptance criteria |
| --- | --- | --- | --- | --- |
| F-SO-01 | Self-registration | Profile | P0 | Verify own number by OTP; choose visibility: Everyone / Registered users only / Nobody (default: Registered users only) |
| F-SO-02 | Who viewed my profile | Who viewed my profile (Premium) | P1 | Lists viewers who were not in incognito; viewers are told views are visible |
| F-SO-03 | Who searched for me | Who searched for me (Premium) | P1 | Count + list of non-incognito searchers |
| F-SO-04 | Incognito mode | Incognito (Premium) | P1 | Views/searches not recorded in the other person's list |
| F-SO-05 | Contact requests | Contact request | P1 | Request a private profile's details; owner approves or denies |
| F-SO-06 | Supporter badge | Premium / Gold badge | P1 | Badge on caller card for supporters; Gold variant |
| F-SO-07 | Delete account | — | P0 | Deletes server profile, reports authored, views; completes ≤ 30 days; immediate de-listing |

### 6.5 Premium-equivalent utilities

| ID | Feature | TC equivalent | Pri | Acceptance criteria |
| --- | --- | --- | --- | --- |
| F-UT-01 | Ghost Call | Ghost Call | P1 | User sets name, number, photo, delay; fake incoming-call screen appears (full-screen notification) |
| F-UT-02 | Announce Caller | Announce calls | P1 | Speaks resolved caller name via TextToSpeech; options: always / headphones only / never |
| F-UT-03 | Call recording | Call recording (Premium) | P2 | Via recording line: user merges call with server number; both parties hear a beep; file downloaded to device and deleted from server ≤ 1 h; disabled where law requires all-party consent unless user confirms announcement |
| F-UT-04 | AI call-screening assistant | Truecaller Assistant | P2 | Conditional call forwarding to assistant number; AI asks purpose; live transcript on phone; user can pick up, reply with templates, or block |
| F-UT-05 | Backup and restore | Backup | P1 | `play`: Google Drive appDataFolder; `foss`: encrypted file via Storage Access Framework; covers block rules, settings, call/SMS metadata, recordings (optional) |
| F-UT-06 | Ad-free | Ad-free (Premium) | P0 | No ads, ever, in any flavor |
| F-UT-07 | Themes | Themes | P1 | Light, dark, system; Gold theme for supporters |
| F-UT-08 | Supporter subscription | Premium / Gold / Family | P1 | `play`: Google Play Billing; `foss`: donation link + self-host unlocks all; family sharing up to 5 |

### 6.6 Business

| ID | Feature | TC equivalent | Pri | Acceptance criteria |
| --- | --- | --- | --- | --- |
| F-BZ-01 | Claim a business number | Truecaller for Business | P0 | OTP/flash-call to the number + document upload; manual review queue |
| F-BZ-02 | Business profile | Business profile | P0 | Name, logo, category, website, hours, call reasons |
| F-BZ-03 | Sender-ID registry import | — | P1 | Importers for public/licensed registries (India DLT headers via partners, SG SSIR, AU ACMA, IE ComReg); mapping header → brand |
| F-BZ-04 | Business dashboard (web) | Business portal | P2 | Manage profile and call reasons; see searches, reports and disputes for own numbers (no call-outcome telemetry) |

## 7. MVP scope (v0.x public beta)

All **P0** features in §6. In plain words: on-device spam list + auto-block + rules, live caller ID with privacy-preserving lookup, reporting and disputes, number search, self-registration, business claim (manual review), full dialer + in-call + call log + contacts, full SMS app with smart inbox, OTP sender ID and OTP safety guard, STIR/SHAKEN, unlisting, account deletion.

MVP launch countries for curated data (templates, registries): Bangladesh, India, United States. The app works in every country; curated data improves where we have it.

## 8. Non-goals (do not build these, and do not let an agent add them)

- **Uploading address books, call logs or SMS bodies**, hashed or not, for any purpose.
- **Names for private numbers from anyone other than the owner.** Community input is type labels only.
- **Scraping** social networks, data brokers, leaked databases, or other apps' databases.
- **Ads, ad SDKs, analytics SDKs that send personal data**, or selling data.
- **Payments / UPI / wallet** (Truecaller Pay was a separate product; out of scope).
- **Fraud insurance** (regulated financial product).
- **Native call recording via Accessibility API.** Google Play has prohibited it since 11 May 2022; we use the recording-line approach only.
- **iOS, web dialer, desktop apps** for v1.0.
- **Credit scoring, lending, or "financial profiles"** of users.
- **Charging businesses to remove a spam label.** Disputes are free and human-reviewed.
- **Root-only features** or hidden system APIs.

## 9. Constraints and dependencies

| Area | Constraint | Source of truth |
| --- | --- | --- |
| Android | `minSdk 29` (call screening role), `targetSdk 36` (Play rule from 31 Aug 2026) | Android developer docs |
| Play policy | SMS/Call Log permissions only as default SMS/Phone handler or approved exception | Play Console Help: SMS and Call Log permissions |
| Play policy | No Accessibility API for call recording | Play policy (May 2022) |
| Telephony | Conditional call forwarding codes vary by carrier | Carrier docs; app shows guided setup |
| Law | Call-recording consent laws vary by country/state | Legal review before enabling F-UT-03 per country |
| Law | GDPR, India DPDP, POPIA, etc. | Privacy review before each country launch |

## 10. Release plan

| Release | Contents | Channel |
| --- | --- | --- |
| v0.1 alpha | Phases 0–5 (blocking + caller ID + backend) | GitHub Releases |
| v0.5 beta | + dialer, SMS, OTP, profiles, business (P0 complete) | GitHub Releases, F-Droid, Play internal/closed testing |
| v1.0 | All P1 | Play production, F-Droid |
| v1.x | P2: recording line, AI assistant, voice scanner, video caller ID, Flash, scam feed | All |

## 11. Open questions

- Hosted-instance pricing for server-cost features (assistant, recording, scanner).
- Which telephony/SIP provider per launch country.
- Access terms for sender-ID registries (India DLT via operators/aggregators).
- Final product name and trademark search.
