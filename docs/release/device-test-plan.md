# Device test plan

Everything that CI's emulators can't check, gathered in one order. Each session needs a bit more equipment than the
one before, so you can stop after any of them and still have useful results. The detailed steps stay in
`docs/testing.md` (section named in each row); this file says what to prepare, in which order to run them, and what to
record. Record results in the sign-off table at the end (copy it into the release's issue or `docs/phase.md`), and
every failure in `docs/testing.md` §6 (bug log) with phone model, Android version and app version.

## Equipment

| Item | Needed from session |
| --- | --- |
| Phone A: Android 14 or newer, **dual SIM** with two active SIMs, a Bluetooth headset | 1 |
| A second phone (any, with a SIM) to call and text phone A | 1 |
| A computer with `adb` and the repository; USB debugging on phone A | 1 |
| The backend from `infra/deploy/README.md` on a VPS with a real domain, and a release build pointing at it | 3 |
| Phone B with OpenCaller (for two-phone tests), on another network than A (one Wi-Fi, one mobile data) | 4 |
| The Play closed test: a play build installed from Play, license testers, Firebase project (issue 130) | 5 |
| A SIM in Bangladesh or India, the telephony setup of `infra/deploy/telephony.md` (Twilio trunk, numbers, keys) | 6 |
| Native speakers of Bengali and Hindi; a lawyer for `PRIVACY.md`, `TERMS.md` and the consent texts | 7 |

Builds: sessions 1–2 use the **foss debug** build (`./gradlew assembleFossDebug`, install with
`adb install -r android/app/build/outputs/apk/foss/debug/app-foss-debug.apk`) because its debug screen shows the
screening latency; sessions 3–7 use the **release** build of a tag (signed, R8), as users get it.

## Session 1: one phone, no server (about 3 hours)

OpenCaller as default phone app and default SMS app on phone A.

| # | Test | testing.md section | Record |
| --- | --- | --- | --- |
| 1.1 | Fresh install, onboarding with every permission denied once, then granted | §5 steps 1–2 | pass/fail |
| 1.2 | Screening latency: 20+ calls (contacts, blocked, unknown, hidden, foreign) | Blocking and screening (Phase 3), "Latency on a phone" (issue 36) | p95 of local decisions, must be < 50 ms |
| 1.3 | Hidden numbers blocked as default phone app (TC-BLK-007); STIR/SHAKEN label if the carrier signs (TC-BLK-014) | Phase 3 | pass/fail, carrier |
| 1.4 | In-call: TC-INCALL-001 on the lock screen, TC-INCALL-002 hold/merge/swap on both SIMs, TC-INCALL-003 Bluetooth | Dialer / in-call / log (Phase 6) | pass/fail each |
| 1.5 | Dial pad: TC-DIAL-003 SIM picker, TC-DIAL-004 USSD/MMI codes, `ACTION_CALL` from another app (issue 73) | Phase 6 | pass/fail |
| 1.6 | Call log by eye (TC-LOG-001); an emergency number typed on the dial pad shows the call button but **do not complete the call** | Phase 6 | pass/fail |
| 1.7 | SMS: TC-SMS-003 MMS picture, group MMS, dual-SIM send, delivery report, quick reply | SMS and OTP (Phase 7), 7a device steps | pass/fail each |
| 1.8 | OTP guard: TC-OTP-005 (during a call, also locked), TC-OTP-009 several codes; scheduled SMS; inbox cleaner | 7b device steps | pass/fail |
| 1.9 | Messenger caller ID (TC-UT-004) with WhatsApp, Telegram, Signal; Ghost Call; Announce Caller (headphones only) | Phase 10 device steps | which app versions work |
| 1.10 | Airplane mode: blocking still works, reports queue | §5 step 5 | pass/fail |
| 1.11 | Upgrade from the previous release (data and settings kept) | §5 step 9 | pass/fail |

## Session 2: accessibility and performance (about 2 hours, plus 24 h unattended)

| # | Test | testing.md section | Record |
| --- | --- | --- | --- |
| 2.1 | TalkBack walkthrough with Largest display and font size | Hardening (Phase 14), device step 1 | findings → bug log |
| 2.2 | Font 200 %: no clipped text on 10 key screens | §5 step 8 | pass/fail |
| 2.3 | Cold start with and without the Baseline Profile (`:benchmark` with the phone connected) | Phase 14 device step 2 | both medians in ms |
| 2.4 | Battery: 24 h with 10 calls and 20 SMS, OpenCaller < 1 % | Phase 14, "24 h battery procedure" (TC-PERF-004) | % drain |

## Session 3: one phone against your server (about 2 hours)

Set up the server first (`infra/deploy/README.md`, record how long it took); build a release tag with
`OPENCALLER_API_BASE_URL` and `OPENCALLER_API_PINS`.

| # | Test | testing.md section | Record |
| --- | --- | --- | --- |
| 3.1 | Sign in with a real SMS code (Twilio), report a number, see it blocked on another phone after the list update | Sync, lookup, reporting (Phase 5) | pass/fail |
| 3.2 | Pinning: a play build with a wrong pin pair can't connect; the right pins work | Phase 14 device step 3 | pass/fail |
| 3.3 | Profile picture upload and display, the verified badge on a call (issues 104, 107) | Phase 8 / Phase 9 device steps | pass/fail |
| 3.4 | Business claim with a real SIM, logo on a real call (TC-BIZ-001/002) | Phase 9 device steps | pass/fail |
| 3.5 | Forced update: set `MIN_APP_VERSION_CODE` above the installed build's, restart the server: the "Update OpenCaller" dialog appears; set it back | Phase 15 (`ClientVersionTest`) | pass/fail |
| 3.6 | Network capture during calls and SMS: no contact, call-log or SMS body leaves the phone (e.g. PCAPdroid, export to Wireshark) | §5 step 10 | pass/fail |

## Session 4: two phones (about 2 hours)

| # | Test | testing.md section | Record |
| --- | --- | --- | --- |
| 4.1 | App calls A ↔ B on different networks, audio both ways, call setup time (TC-VOIP-001); Flash messages (002); video caller ID recorded with the camera (003) | Phase 11 device steps (issues 128–131, 138) | setup time p95 (< 3 s), pass/fail |
| 4.2 | App call on an Android 10–13 phone (Jetpack Telecom's ConnectionService path, issue 138) | Phase 11 | pass/fail |
| 4.3 | Family: invite, consent, an OTP warning on the member's phone reaches the admin within 10 s (TC-FAM-002); daily counts; leaving deletes the data | Phase 12 device steps, §5 step 11 | seconds, pass/fail |
| 4.4 | Scam feed in the phone's language; business dashboard in a browser | Phase 12 device steps, §5 steps 12–13 | pass/fail |

## Session 5: the Play closed test (runs for at least 14 days)

| # | Test | testing.md section | Record |
| --- | --- | --- | --- |
| 5.1 | License tester buys Supporter, entitlement active after the server's check (TC-BILL-001) | Phase 10 device steps (issue 117) | pass/fail |
| 5.2 | FCM push for an app call with the app closed | Phase 11 | pass/fail |
| 5.3 | Drive backup and restore on a fresh install (TC-UT-003) | Phase 10 device steps | pass/fail |
| 5.4 | Crash-free sessions ≥ 99.5 % in Android vitals over the test (issue 173) | Phase 14 device step 5 | % |

## Session 6: telephony (only where the operator turns it on)

Legal review of recording in the country first (`infra/deploy/telephony.md`, "Legal switch"). SIP over TLS and SRTP
on (telephony.md → Security, issue 148).

| # | Test | testing.md section | Record |
| --- | --- | --- | --- |
| 6.1 | Recording line: beep heard, file on the phone, server copy gone within 1 h (TC-REC-001) | Phase 13 device steps | pass/fail |
| 6.2 | AI assistant answers a forwarded call, transcript streams (TC-AST-001) | Phase 13b device steps | cost per screened minute |
| 6.3 | Voice scanner on a real call, wording as likelihood (TC-SCAN-001); a call over 2G/GSM (issue 166) | Phase 13c device steps | result, false positives |

## Session 7: people, not phones

| # | Task | Where |
| --- | --- | --- |
| 7.1 | Native speakers read every screen in Bengali and Hindi and fix `values-bn` / `values-hi`; then the scam-cue phrases | issues 165, 171; Phase 14 device step 4 |
| 7.2 | A lawyer reviews `PRIVACY.md`, `TERMS.md` and the family consent texts; publish the privacy policy at a stable URL | issue 110; `play-release-checklist.md` |

## Sign-off

| Session | Date | Phone(s), Android version | Build (tag, flavor) | Result | Bugs filed |
| --- | --- | --- | --- | --- | --- |
| 1 | | | | | |
| 2 | | | | | |
| 3 | | | | | |
| 4 | | | | | |
| 5 | | | | | |
| 6 | | | | | |
| 7 | | | | | |
