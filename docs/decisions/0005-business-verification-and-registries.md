# ADR 0005 — Business verification, the business directory and registry imports

- Status: Accepted
- Date: 2026-11-03
- Phase 9: business claims with admin review, verified businesses on the phone, sender-ID importers, OTP-source
  curation (`docs/implementation_plan.md`)

## Context

F-BZ-01 asks that a business proves it controls a number ("OTP/flash-call to the number") and sends documents for
manual review. F-BZ-02 shows the verified business on calls, and F-BZ-03 imports sender-ID registries. "Block
non-contacts" needs to know a verified business before any network lookup, and offline. ADR 0001 says names never come
from other people's address books. A business's name is the business's own claim, checked by a person.

## Decision

### Proof of control: a code to the claimed number, by SMS or a phone call

The server sends a 6-digit code to the claimed number, by SMS or by a Twilio voice call that reads it out three times
(for landlines). A wrong code counts against 5 attempts and a code lasts 5 minutes, like sign-in. Each number gets 3
codes an hour and each user 5 claims a day. Only the user who started the claim can enter its code. Documents can't
be uploaded and the claim can't be submitted until the code is confirmed (TC-BIZ-001). One claim per number can be
open at a time.

A flash call (calling from a number whose last digits are the code) is not used. It needs a pool of caller numbers we
own; Twilio only calls from our verified numbers. A small pool makes the code guessable, and a missed call from an
unknown number is the pattern OpenCaller teaches users to distrust. The voice call covers the landline case that
flash calls are usually meant for.

### Documents

Documents (PDF, JPEG, PNG ≤ 10 MB, at most 5) and an optional logo (PNG or JPEG ≤ 200 KB, re-encoded on the phone) go
to private MinIO keys through 10-minute pre-signed PUT URLs. The upload URL fixes the content type. On submit the
server checks each key was issued for this claim, exists, has that type and is within the size. Only admins see the
documents, in the admin web, served as attachments under a sandboxing CSP. They are deleted when the claim is
withdrawn, and 90 days after an approval or rejection (the approved logo stays as the business's logo). A user who
deletes their account loses their claims' files at the purge.

### Publishing an approved business

Approval creates the business, attaches the number and caps the number's spam score below the list threshold (0.39,
issue 49), so it leaves the spam lists at the next build. The business then appears in:

- bucket entries (`business` with id, name, call reason, logo path), like any lookup;
- `GET /v1/business/{id}` (profile) and `/logo`;
- `GET /v1/businesses/{iso}?since=`: the verified numbers of a country, with revoked ones flagged `removed`.

Businesses asked to be shown, so their numbers, names and logos are public. The directory says nothing about users.

### The directory on the phone

The daily update downloads the directory of the phone's country (changed rows only) into Room (`verified_business`,
version 3) and each business's logo into private storage. A logo must be a PNG or JPEG of at most 200 KB, from the API's
own logo path. Screening reads the directory locally:

- a verified business rings with "block non-contacts" and isn't blocked by the live lookup;
- the caller card and SMS sender name it, with its logo, call reason and badge;
- the user's own rules and the foreign-number setting still apply.

If the directory can't be read, the decision fails open (the call rings). Logos are fetched in bulk with the
directory, never during a call, so no request reveals who is calling. On a country change the old directory is
dropped.

### Admin web

The admin web is server-rendered HTML on its own port (`ADMIN_WEB_PORT`; off when unset; compose doesn't publish it).
Each request checks, in this order:

1. The IP allowlist (`ADMIN_WEB_ALLOWED_NETWORKS`, CIDR blocks, socket address only; forwarded headers aren't
   trusted). A request from outside it gets 403.
2. Basic auth with one admin user, compared in constant time, password at least 32 characters (401 otherwise).
3. For every POST, an HMAC CSRF token.

Responses are `no-store`, `X-Frame-Options: DENY` and `no-referrer`, under a strict CSP with no scripts. Every action
(approve, reject, revoke, curation change, import) is written to `admin_audit` with the admin, IP, target and result.

### Registries

Sender IDs come through an importer interface (`fetch`, `map`, `upsert`). The CSV importer reads manually obtained
extracts (`<IMPORT_DIR>/<source>/<ISO>.csv`, or an upload in the admin web). A complete extract replaces that
source's rows for its country; removed rows keep a `deleted_at` tombstone, so phones remove them too. A daily job
runs every importer and records each run in `import_runs`.

No documented, public access exists for the India DLT, Singapore SSIR, Australia ACMA or Ireland ComReg registries
(owner's decision, 2026-11-02). Each has a placeholder importer that records `unavailable` with the reason, so the
gap stays visible. A real importer replaces a placeholder only after its source is checked and tested.

SMS Retriever app hashes and flash-call verification ranges are curated by hand in the admin web, also with
tombstones.

## Consequences

- Codes by phone call need `TWILIO_VOICE_FROM` in production; without it the server refuses `voice` claims (400) and
  only SMS works. Development logs the code.
- The directory grows with the number of verified businesses in a country. Pages hold 5,000 rows, and rows sharing an
  update time may straddle pages, as for sender IDs (issue 51).
- An admin account is a single shared credential. Per-admin accounts and 2FA belong to the production hardening of
  Phase 15, along with a reverse proxy and its trusted headers (issue 37).
