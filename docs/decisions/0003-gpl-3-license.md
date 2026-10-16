# ADR 0003 — GPL-3.0-or-later license

- Status: Accepted
- Date: 2026-10-15
- Reviewed: 2026-10-16 (Phase 0), no changes; remains Accepted

## Context

The dialer and SMS screens may reuse code from Fossify Phone and Fossify Messages, which are GPL-3.0. We want forks (including commercial ones) to stay open.

## Decision

License the whole repository under GPL-3.0-or-later. Keep upstream copyright notices in any file derived from Fossify code and list them in `NOTICE.md`.

## Consequences

- Compatible with F-Droid.
- Libraries must be GPL-compatible (Apache-2.0, MIT, BSD are fine). Check before adding any dependency with another license.
- Bundled ML models and datasets need licences that permit redistribution; record each in `NOTICE.md`.
