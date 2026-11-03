# Play Console: Data safety (v0.5.0-beta, play flavor)

Fill the form for the build you upload. The release workflow builds with a server only when the
`OPENCALLER_API_BASE_URL` secret is set.

- **Built without a server** (secret empty): the app sends nothing to OpenCaller, so answer *"No"* to "Does your app
  collect or share any of the required user data types?". Everything below applies only to a build with a server.
- **Built with a server**: use the answers below.

Play counts data as *collected* when it leaves the phone. Data processed only on the phone is not collected. That
covers contacts, the call log, SMS and MMS content and the caller card. Data sent to a service provider that processes
it for us (Twilio sends the sign-in code text or call) is not *sharing*.

## Overview questions

| Question | Answer |
| --- | --- |
| Does your app collect or share any of the required user data types? | Yes |
| Is all of the user data collected by your app encrypted in transit? | Yes. HTTPS only; the play build pins the server's key plus a backup key. |
| Do you provide a way for users to request that their data is deleted? | Yes. In the app: Me → My profile → Delete account (the server removes the account within a day). Without the app: the server's "unlist my number" page. |

## Data types

Everything is **collected, not shared**, and **processed for the stated purpose only**. Nothing is used for
advertising, marketing or analytics. Sign-in is optional; without it nothing below is collected except that the
app downloads public lists.

| Category → type | Collected? | Required or optional | Purposes | What exactly |
| --- | --- | --- | --- | --- |
| Personal info → Phone number | Yes | Optional (only when the user signs in, claims a business or unlists a number) | App functionality; Account management; Fraud prevention, security and compliance | The user's own number, verified by a code, to sign in; a business number being claimed; a number being unlisted. Also the numbers a user reports, searches or disputes (they choose to send them). |
| Personal info → Name | Yes | Optional | App functionality | The display name a user adds to their profile, shown to others only as their visibility setting allows; a claimed business's legal and display name. |
| Photos and videos → Photos | Yes | Optional | App functionality | A profile picture (re-encoded on the phone, JPEG 512 px); a business logo. |
| Files and docs → Files and docs | Yes | Optional | App functionality; Fraud prevention, security and compliance | Documents a business sends with its claim (PDF, JPEG or PNG). Only reviewers see them; deleted 90 days after the decision. |
| App activity → In-app search history | Yes | Optional | App functionality | Numbers the user searches for. A search is shown to the searched number's owner (supporters) unless the user is incognito; kept 90 days. |
| App activity → Other user-generated content | Yes | Optional | App functionality; Fraud prevention, security and compliance | Spam reports (number, label, category, optional comment), disputes, contact requests. |
| App activity → Other actions | Yes | Optional | App functionality | Profile views (who viewed which profile, unless incognito); kept 90 days. |
| Device or other IDs | Yes | Optional (with sign-in) | Account management; Fraud prevention, security and compliance | A server-generated device ID tied to the session's refresh tokens. No hardware IDs, no advertising ID. |
| Financial info → Purchase history | Yes | Optional (play build, supporters only) | App functionality; Account management | The supporter subscription: Google Play's purchase token, base plan, state and paid-until date, checked with Google to unlock supporter features. No card or payment details (Google Play handles payment). |

## Not collected (answer "No")

Location, financial info other than the supporter subscription, health and fitness, messages (emails, SMS or MMS, other messages), audio, calendar,
contacts, app info and performance (no crash logs or diagnostics), web browsing, and the call log: none of these
leave the phone.

Explain if asked:

- **Live caller ID lookup:** only the country calling code and the first 16 bits of the number's SHA-256 hash are
  sent. That is thousands of numbers per bucket (k-anonymity), not the number.
- **Spam list and registry updates:** downloads of public lists. They send the user's country, nothing else.
- **Backups:** encrypted on the phone with the user's passphrase (AES-256-GCM) before they are saved to a file or to
  the user's own Google Drive app folder (play). OpenCaller's server never receives them, and Google stores only
  ciphertext. They hold settings, block rules, the screening log and message categories, never message bodies.
- **Messenger caller ID:** reads WhatsApp, Telegram and Signal call notifications on the phone only, with the user's
  notification access; nothing from them is sent anywhere.
- **IP addresses:** used only for rate limits (kept in memory for up to an hour); not stored with the account.

## Before submitting

- Play needs a **privacy policy URL** for an app with SMS and Call Log permissions. None is published yet
  (implementation plan Phase 15: privacy policy and terms); `docs/phase.md` lists it as an open issue.
- Re-check this form if the build adds Firebase Cloud Messaging (planned for play sync pushes): that would add
  *Device or other IDs → push token*.
