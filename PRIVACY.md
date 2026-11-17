# OpenCaller privacy policy

> **Draft for legal review (Phase 14, 2026-11-16).** Not yet published. Written from the code and
> `docs/architecture.md` §10; the owner's lawyer must check it against the laws of each launch country (for example
> Bangladesh, India, the EU's GDPR and the UK GDPR) before it is published at the URL Google Play requires. Text in
> [square brackets] must be filled in by the owner. Keep it in step with `docs/release/play-data-safety.md`.

Last updated: [date of publication]

## Who we are

OpenCaller is an open-source app (GPL-3.0) for caller ID, spam blocking, calls and messages on Android. This policy
covers the OpenCaller app and the OpenCaller server run by [legal name of the operator, address, contact email]
("we").

Anyone can run their own OpenCaller server. If your app is connected to a server run by someone else (your
employer, a community, yourself), that operator decides how the server handles your data, and their policy applies
to it, not this one.

## The short version

- **Your contacts, your call log and your text messages stay on your phone.** OpenCaller reads them to show who is
  calling and to sort your messages, on your phone. It never uploads them.
- **Caller ID works without revealing who is calling you.** For an unknown number the app asks the server about a
  group of thousands of numbers (by a short fingerprint of the number), not the number itself.
- **No ads, no trackers, no crash-report or analytics tools.** OpenCaller contains none.
- **Signing in is optional.** Without an account the app still blocks calls with your own rules and the public spam
  list.
- **You can delete your account** in the app at any time, and you can ask for your number to be removed from
  OpenCaller without the app.

## What stays on your phone

These are read or stored only on your phone, in an encrypted database, and are never sent to us:

- your contacts, call history and text and picture messages (SMS and MMS), including sign-in codes you receive;
- your block and allow rules, the list of calls OpenCaller blocked, and how each message was sorted;
- recordings you saved, conversations screened by your call assistant, and the history of your family alerts;
- notifications of WhatsApp, Telegram and Signal calls, if you turn on messenger caller ID.

OpenCaller backs these up only when you ask, encrypted with a passphrase only you know, to a file or to your own
Google Drive. We can't read your backups.

## What we receive, and why

| What | When | Why | How long we keep it |
| --- | --- | --- | --- |
| Your phone number | When you sign in (we send a code by SMS or a call) | Your account | Until you delete your account |
| A device ID we create, sign-in tokens | While you are signed in | Keeping you signed in | Until you sign out or delete your account |
| A push address (Firebase Cloud Messaging on Google Play builds, or your UnifiedPush distributor) | If you use app calls, Flash messages, family alerts or recordings | To tell your phone that something is waiting. The push itself contains only a random code; your phone then fetches the item from us | Until you sign out |
| A short fingerprint of an unknown caller's number (country code and 16 bits of its hash) | When an unknown number calls you | Caller ID without revealing the number | Not stored |
| Numbers you report as spam or not spam, with the label and an optional comment | When you report | Warning others about spam | [18 months, then only totals: planned, not enforced yet (`docs/phase.md` issue 52); today until you delete your account] |
| Numbers you search for | When you search | Showing the result; the owner of a number can see that someone searched (not if you search incognito) | 90 days |
| Your profile: display name, picture, visibility, short video caller ID | If you create one | Showing your name to people you call, as your visibility setting allows | Until you change or delete it |
| Contact requests, profile views | When you use them | Showing them to the people involved | 90 days for views |
| Business claims and their documents | If you claim a business number | Checking the claim | Documents: 90 days after the decision |
| Family protection: your group, the name you are known by in it, your consent, the alerts and two daily counts you chose to share | Only if you join a family group and switch sharing on | Warning your family admin when someone may be scamming you | Alerts 30 days, counts 8 days; deleted when you switch sharing off or leave |
| App calls and Flash messages: who called or pinged whom, and when | When you use them | Connecting the call, delivering the message | A day (Flash messages: until delivered, at most a day). Call audio is never recorded |
| Call recordings | Only when you tap Record and confirm | Recording the call for you | Until your phone has saved it, then deleted within an hour (24 hours at most) |
| Transcripts of recordings | Only if you turn transcripts on | Showing you what was said | Deleted with the recording |
| Calls your call assistant answers: the caller's voice, while the call lasts | Only for calls you forward to your assistant number | The assistant talks to the caller and shows you the conversation; the caller's number and the conversation are sent to your phone only | We keep minutes used per month, and a call record without any phone number for a day |
| A few seconds of the other person's voice | Only when you tap Scan voice during a call | Checking whether the voice may be generated | Not stored; we keep the result (a probability and scam phrases) for a day, without any phone number |
| Your Google Play subscription | If you become a supporter (Google Play builds) | Unlocking supporter features | While you are a supporter, and as tax law requires |
| Your internet address | With every request | Limiting abuse (rate limits) | Up to an hour, in memory |

We never ask for or store your location, your contacts, your call log, your messages, advertising IDs or hardware
IDs.

## Recording and screening calls

Laws on recording and listening to calls differ by country and some require everyone's consent. OpenCaller plays a
beep and an announcement to both people when a call is recorded, and a beep when a voice is scanned, and offers
these features only in countries where [the operator] has approved them. You are responsible for using them lawfully.
The call assistant tells every caller that they are speaking to an automated assistant.

## Who else processes data

We don't sell data or share it for advertising. These companies process data for us, only to provide the service:

- [Hosting provider, country] runs our servers.
- [SMS and voice provider, e.g. Twilio] sends sign-in codes and connects the recording line, the call assistant and
  the voice scanner to the phone network.
- [Speech and language providers chosen by the operator, e.g. …] process the caller's voice and words while the call
  assistant answers.
- Google: Firebase Cloud Messaging delivers the random push codes (Google Play builds only), Google Play handles
  supporter payments, and Google Drive stores your encrypted backup if you choose it.

[If data leaves the user's country: describe the safeguards, e.g. standard contractual clauses.]

## Your rights and choices

- **Delete your account:** Me → My profile → Delete account. Your profile disappears at once and the rest (your
  reports, picture, devices, family groups) is removed within a day.
- **Remove your number from OpenCaller without the app:** [server URL]/unlist.
- **Dispute a spam label** on your number from its profile in the app.
- **Ask for a copy or a correction** of your data, or object to how we use it: write to [contact email]. [Add the
  rights and the supervisory authority that apply in each launch country.]
- Permissions can be turned off in Android's settings at any time; the features that need them stop working.

## Children

OpenCaller is not meant for children under [13/16, per the launch countries' law]. We don't knowingly hold their
data; write to us if you think we do.

## Security

Data on your phone is encrypted with keys kept in Android's secure hardware store. Data between your phone and us
is encrypted (HTTPS; Google Play builds also check our server's key). Our security review is public:
`docs/security.md` in the source code.

## Changes

We will announce changes to this policy in the app and on [URL] before they take effect.

## Contact

[Operator's name, address, email; data protection officer or representative where the law requires one.]
