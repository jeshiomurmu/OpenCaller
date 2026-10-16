# ADR 0001 — Consent-first identity data

- Status: Accepted
- Date: 2026-10-15
- Reviewed: 2026-10-16 (Phase 0), no changes; remains Accepted

## Context

Caller ID quality depends on name data. The incumbent built its database largely from users' uploaded address books. Courts and regulators (Nigeria, South Africa, EU under GDPR) have challenged that one user's consent covers the people in their contacts. An open-source project with public code and volunteer servers is an easy target for regulators and for misuse as a stalking tool.

## Decision

Names are shown only when they come from: the number's owner (self-registration with OTP), a verified business, an official sender-ID registry, or the user's own device (contacts, on-device inference). Community input is limited to caller *type* labels and spam reports. No contact-book, call-log or SMS-body upload, hashed or otherwise.

## Consequences

- Lower name coverage at launch; spam blocking ships first because it needs no names.
- Strong privacy story; simpler compliance with GDPR, DPDP, POPIA.
- Requires business-claim and registry-import work (Phase 9) to reach useful coverage.
