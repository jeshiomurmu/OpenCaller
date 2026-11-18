# Security policy

OpenCaller handles calls, messages and phone numbers, so security reports get priority over other work.

## Reporting a vulnerability

**Please don't open a public issue.** Report privately through GitHub:
[Security → Report a vulnerability](https://github.com/jeshiomurmu/OpenCaller/security/advisories/new)
(GitHub's private vulnerability reporting; only the maintainers see the report).

Include what you can of:

- the affected part (Android app and flavor `foss`/`play`, server, agents, deploy files) and version or commit;
- what an attacker can do, and what they need first (a malicious app on the phone, a network position, an account,
  a crafted SMS or call, …);
- steps to reproduce or a proof of concept; logs with phone numbers and codes removed.

What happens next:

| When | What |
| --- | --- |
| within 3 working days | we confirm we received it |
| within 10 working days | first assessment: accepted or not, severity, planned fix |
| fix released | a GitHub security advisory credits you (unless you'd rather not be named), a CVE if it applies |

We ask for 90 days, or until a fix is released if sooner, before you publish details. We won't take legal action
against research done in good faith that respects users' privacy: test only with your own accounts and numbers,
don't read or keep other people's data, and don't degrade the hosted service (no load tests or spam against it).

## Supported versions

| Version | Supported |
| --- | --- |
| the latest release (`vX.Y.Z` on GitHub, F-Droid, Google Play) | yes |
| older releases | no: update to the latest |
| self-hosted servers | the latest release's `infra/deploy` setup; keep `OPENCALLER_VERSION` current |

## Scope

In scope: the Android app, the server (`backend/server`), the agents (`backend/agents`, `ml/voice-spoof`), the
deploy files (`infra/`) and the release pipeline (`.github/workflows`).

Out of scope: third-party services (Twilio, Google Play, Firebase, LiveKit Cloud, push distributors) unless
OpenCaller uses them insecurely; spam numbers or wrong caller names (report those in the app); findings that need a
rooted phone or physical access to an unlocked phone; missing hardening headers without an exploit.

The threat model and the controls already in place are in `docs/security.md` (OWASP MASVS v2 review) and the privacy
invariants in `docs/architecture.md` §10.
