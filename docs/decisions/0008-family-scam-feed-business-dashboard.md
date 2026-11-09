# ADR 0008 — Family protection, scam feed and the business dashboard

- Status: Accepted
- Date: 2026-11-09
- Phase 12: family groups with consent, OTP-during-call alerts and a daily blocked-scam summary, admin-curated scam
  cards, the business dashboard (F-BZ-04) (`docs/implementation_plan.md`)

## Context

Family protection (F-PR-14) lets someone like Ama's son see that her phone is protecting her and hear about it when a
scammer asks for her one-time code during a call. That means a server and another person learn things about Ama's
phone, so it has to be her choice, made on her phone, about a small, named set of facts. The scam feed (F-PR-15)
needs curated content per country. The business dashboard (F-BZ-04) lets verified businesses keep their profile
current and see how people react to their numbers, without the server ever collecting what happened on calls.

Owner decisions of 2026-11-09:

- family protection is free for everyone signed in;
- a consenting member's phone sends, once a day, two counts for that day (calls blocked, messages filtered as spam or
  scam); the admin sees the last 7 days;
- the server counts explicit searches of verified-business numbers per number and day, without the searcher, kept
  90 days, for the dashboard;
- v1.0.0 is prepared, and tagged once the signing secrets are confirmed.

## Decision

### Family groups: only the member can make themselves a member

- A group has an admin and at most 5 people in all; a person is in at most one group.
- The admin makes a single-use code (8 characters without look-alikes, 48 hours; the server keeps only its SHA-256) and
  passes it on outside the app. There is no endpoint that takes another person's phone number or adds someone.
- The member opens the code on their own phone. `GET /v1/family/invites/{code}` shows the group, the admin's label and
  the consent version without changing anything. The app's consent screen says in plain words what the admin will see
  (alerts and daily counts, each switchable) and what they never see (who called or texted, numbers, message text,
  contacts, the call log, location). `POST /v1/family/join` needs `consent: true` and the consent version the server
  expects; the row records `consented_at`, `consent_version` and the two switches (TC-FAM-001).
- A member can change the switches or leave at any time; turning a switch off or leaving deletes what the server held
  of it. The admin can remove a member; the admin leaving ends the group.
- The admin shares nothing; alerts and counts only flow from members to the admin.

### What a member's phone sends, and only when the member shares it

- **Alerts** (`POST /v1/family/alerts`): the kind only, `otp_during_call` or `otp_burst`, sent when the phone's OTP guard
  warns its own user. The server stores the kind, the member and the time (30 days) and pushes the admin like an app
  call or a Flash message: the push carries a random item id, the admin's phone fetches what it means (ADR 0007).
  The target is "the admin knows within 10 seconds" (TC-FAM-002).
- **Daily counts** (`PUT /v1/family/counts/{day}`): calls blocked and messages filtered as spam or scam that day. The
  phone computes them from its own screening log and message categories; the server keeps 8 days.
- Nothing else: no numbers, no names, no message text, no contacts, no call log, no location. The privacy invariants
  (`docs/architecture.md` §10) gain one line for this.

### Scam feed

- Admins write cards (country, language, title ≤ 80, text ≤ 600, optional https link, optional last day) in the admin
  web; a card is a draft until published, and every change is in the audit log.
- `GET /v1/scam-feed/{iso}` is public and cacheable for an hour; the request names only the country. The app shows its
  country's cards in the Protection tab, in the reader's language when there is a card in it, else in English, and
  keeps the last copy for offline use.

### Business dashboard

- `/portal` on the API port: plain HTML, no JavaScript, a strict Content-Security-Policy, no framing. Sign-in sends a
  code by SMS to a number of an approved business (the same page for any number); the session is a random token in an
  HttpOnly, SameSite=Strict cookie limited to `/portal` (Secure outside development; the server keeps its SHA-256),
  12 hours; every form carries the session's CSRF token.
- The business can change its website, opening hours and each number's call reason; name, category and logo were
  checked in the claim and change through a new claim. Changes touch the business's numbers so apps pick them up
  with their next directory update.
- It sees, per number and day for 30 days: explicit searches (counted without the searcher), spam reports and not-spam
  reports; and its disputes (filed, status, decided). Not who reported or what they wrote.
- No call-outcome telemetry: apps send nothing about whether a call was answered, declined or blocked by the person,
  and the page says so.

## Consequences

- The admin learns, for each sharing member, how many calls and spam messages their phone stopped each day, and when
  a code arrived during a call. That is the purpose and the member agreed to it; it is still personal data and is
  listed in the Data safety form.
- An alert reaches the admin within seconds only with a push provider (FCM or a UnifiedPush distributor); without one,
  the admin sees it when the app opens.
- Invite codes have 40 bits; with 20 tries an hour per account, guessing one within its 48 hours is out of reach.
- Search counts let a business see interest in its numbers but not who searched; they could be inflated by someone
  searching repeatedly (50 searches a day per account).
- A business whose approval is revoked loses its dashboard sessions at once.
