# ADR 0004 — Viewer and searcher logging

- Status: Accepted
- Date: 2026-11-01
- Phase 8 exit item: privacy review of "who viewed my profile" and "who searched for me" (`docs/implementation_plan.md`)

## Context

F-SO-02/03 let supporters see who viewed their profile and who searched for their number. To answer, the server has to remember that one user looked at another. That record is personal data about two people. It shows interest, and it could be misused to track someone or to unmask a person who chose to hide. F-SO-04 (incognito) and F-SO-07 (delete account) limit it. The privacy invariants (`docs/architecture.md` §10) forbid anything about calls or contacts reaching the server, and ADR 0001 forbids names from anyone but the owner.

## Decision

### What is stored

One row in `profile_views` per event: viewed user ID, viewer user ID, kind (`view` or `search`) and time. Nothing else is stored: no number typed, IP address, device or search result. Both users are user IDs, so only searches and views by signed-in users can be recorded.

A row is written only when all of these hold:

- the viewer opened the profile (`GET /v1/profiles/{e164}`) or searched for the number (`GET /v1/numbers/{e164}`). Live caller ID never records anything: buckets don't reveal which number was looked up (§4.6), and incoming calls are not "views";
- the number belongs to an undeleted user who isn't the viewer;
- the viewer is not incognito (checked when the view or search happens; turning incognito on later doesn't hide earlier rows);
- the same viewer hasn't been recorded for the same user and kind in the last 24 hours. Repeats are dropped, so a list can't be flooded and repeated looks don't build up a detailed timeline.

### Retention

- Rows older than 90 days are deleted by the daily `retention` job (`RetentionJob`), matching §7 "profile views 90 days". Lists and counts only cover the last 90 days, even before the job runs.
- When either user deletes their account (`DELETE /v1/me`), every row involving them is deleted at once, not at the purge.
- Backups are outside this decision. They must expire within 30 days too (Phase 15 operations).

### Who can see it

- Only the viewed user, through `GET /v1/me/viewers` and `/v1/me/searchers`. These are behind the supporter check; until billing exists (Phase 10) the check reads `users.supporter_tier`, which nothing sets yet. Any signed-in user sees only the counts in `GET /v1/me`.
- Each entry shows the visitor's name only if the visitor's own visibility lets signed-in users see it (`everyone` or `registered_only`). It shows the supporter badge with the name. It never shows the visitor's number. A visitor with visibility `nobody` appears as an unnamed entry: the owner learns that someone looked, not who.
- Server operators can read the table. There is no admin endpoint for it, and logs never contain it (numbers are redacted, §10.3).

### Telling people

- The app tells users, before they search and on profiles, that the owner may see they looked unless incognito is on (F-SO-02 "viewers are told views are visible").
- The incognito switch says that it stops new records about you, and that it doesn't hide what was already recorded.

### Related choices in the same feature

- **Contact requests** (F-SO-05) answer every valid number the same way (`{"status":"sent"}`). They reveal neither whether the number is registered nor whether its owner denied an earlier request. Only owners with visibility `nobody` receive them. The owner sees the requester's number and name, which asking shares, and the app says so before sending. A denial is never reported. The requester may ask again after 30 days.
- **Badges** (F-SO-06) appear only next to a visible name, so a badge can't reveal a hidden user.
- **Public profiles** (`GET /v1/profiles/{e164}`) have the same shape for unregistered numbers and hidden profiles.

## Consequences

- Supporters get the feature with a narrow footprint: two IDs, a kind and a time, for 90 days at most, and at most one row a day per pair.
- Hidden users stay hidden: no number, no name and no badge leak through lists, profiles or contact requests.
- An unnamed entry still tells the owner that "someone" looked. That is the nature of the feature, and incognito is the remedy.
- Incognito is free for everyone, not a supporter perk, because it protects the person doing the looking.
- When billing arrives (Phase 10), only `Entitlements` changes; what is stored and shown stays as decided here.
- Revisit if a "who viewed" result ever needs more than IDs and time, or if retention changes. Either needs a new ADR.
