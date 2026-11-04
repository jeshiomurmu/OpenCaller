# ADR 0007 — App calls, opaque pushes, Flash messages and video caller ID

- Status: Accepted
- Date: 2026-11-04
- Phase 11: LiveKit + TURN, `/v1/voip/token`, app-to-app calls with Jetpack Telecom, push-to-ring, Flash messages,
  video caller ID (`docs/implementation_plan.md`)

## Context

Phase 11 lets two OpenCaller users call and ping each other over the app, and lets a user show a short video when
they call. Every one of these needs the server to wake the other phone, and a push service (Google's FCM, or a
UnifiedPush distributor) sees each push. `docs/rules.md` §2 allowed FCM only for data-less "sync" pushes. Owner
decisions of 2026-11-04:

- the call-invite push carries only an opaque, random ID (rules.md §2 amended);
- video caller ID plays on app calls and on normal phone calls, the latter without revealing the caller to the
  server;
- app calls and Flash messages reach a signed-in user by default, with a setting to turn them off;
- Flash messages are predefined texts, without location;
- JitPack is allowed for exactly one module, `com.github.davidliu:audioswitch`, which the LiveKit SDK needs;
- the F-Droid build leaves out app calls (LiveKit ships prebuilt WebRTC and Rust libraries); GitHub foss APKs and
  play keep them.

## Decision

### Pushes carry a random id, nothing else

The server stores a *push item* (`push_items`: user, kind, the invite or Flash message it stands for, a short expiry)
and pushes only the item's random UUID:

- **play:** FCM HTTP v1, `data: {"id": "…"}`, high priority for calls (TTL 45 s), normal for Flash (TTL 24 h);
- **foss:** Web Push (RFC 8291 `aes128gcm`, VAPID RFC 8292) to the endpoint of the user's UnifiedPush distributor,
  body `{"id":"…"}`, encrypted for the phone's key.

The phone fetches `GET /v1/notifications/{id}` with its access token to learn what the item is. FCM and the
distributor never see who calls or pings whom, a name, a number or a kind. A phone that missed a push fetches
`GET /v1/notifications` when the app opens. Endpoints the push service reports gone (404/410) are removed. In
production the server sends Web Push only to `https://` endpoints on public addresses (no SSRF into its own network);
the check runs at registration and again before each push.

### App calls: LiveKit for media, the server only brokers

`POST /v1/voip/calls` creates an invite that rings for 45 s. Both parties join a LiveKit room named after their
sorted user IDs and a 96-bit random nonce, each with a 10-minute token (`POST /v1/voip/token`) that allows only that
room, audio only (`canPublishSources: microphone`). Audio flows through LiveKit (DTLS-SRTP); the server never relays
or records it. Invites are deleted a day after they were made (hourly `voip-cleanup` job), push items when they
expire, so the server keeps no history of who called whom.

On the phone, the call is a self-managed Jetpack Telecom call (`androidx.core:core-telecom`) in a `phoneCall`
foreground service: other calls hold or end it, Bluetooth and cars can answer it, and Telecom routes its audio
(LiveKit's own routing is off). The microphone is asked for when the user starts or answers an app call, never
before.

### Who can be reached

A signed-in user with a push endpoint is reachable by anyone with their number who uses OpenCaller, unless they turn
it off (`PUT /v1/voip/settings`). Every reason a number can't be reached (no account, deleted, turned off, no push
endpoint, the caller's own number) gives the same `404 not_reachable`, so calls and Flash messages can't be used to
find out who has an account or who turned it off. Calls are limited to 30 per user per hour, Flash messages to 60 per
sender and 10 per pair per hour.

### Flash messages

Six predefined kinds (call me, on my way, running late, can't talk, I've arrived, I'll call you back); no free text,
no location. The text is shown in the reader's language. A message waits on the server only until the recipient's
phone fetches it (then it is deleted) or 24 h. Both phones keep their copy locally (`flash_message`, 30 days).

### Video caller ID

- **Recording:** the user records at most 10 s with the front camera (CameraX), without audio; the app never asks
  for the microphone for this.
- **Upload:** a pre-signed `PUT` to MinIO (`video-uploads/…`, `video/mp4`, at most 20 MiB), then
  `POST /v1/me/video`.
- **Transcoding:** a separate worker container (the same server binary, run as `transcoder`, with ffmpeg) claims
  jobs with `FOR UPDATE SKIP LOCKED`. It re-encodes to H.264, at most 480 px wide, 24 fps, at most 10 s, no audio,
  no metadata (`-map_metadata -1`), files only (`-protocol_whitelist file`), with a timeout. The clip is published
  under a fresh random id; the old clip stays until the new one is ready. The container has a read-only file
  system, no capabilities and no published port, so a malicious upload that breaks ffmpeg reaches little.
- **Who sees it:** the clip id appears in lookup bucket entries (`NumberInfo.videoId`) and in a call invite (callee
  only) with the same visibility rule as the name. Three reports by different users hide a clip.
- **Normal phone calls without telling the server who rang:** the live lookup already fetches a k-anonymous bucket.
  The phone downloads *every* clip that bucket names (`POST /v1/videos/links`, no sign-in, then pre-signed `GET`s),
  so the server learns no more than the bucket. Downloads run only on Wi-Fi unless the user allows mobile data. The
  in-call screen plays the caller's clip muted, from the cache, if it is there in time.

### Builds

LiveKit's audio-routing dependency is published only on JitPack. Gradle's `exclusiveContent` lets JitPack serve that
one module and nothing else. `-Popencaller.voip=false` leaves `feature:voip`, LiveKit and the JitPack repository out
of the build (the F-Droid recipe sets it); push, Flash messages and video caller ID stay. FCM is in the play build
only, configured from Gradle properties (no `google-services.json`). Firebase's Firelog upload backend
(`transport-backend-cct`) is excluded; nothing else of Firebase is used.

## Consequences

- FCM (play) and the distributor (foss) learn that the phone got a push and when, not from whom or why. Google
  learns the FCM token, a device identifier (Play Data safety: "Device or other IDs").
- LiveKit and TURN see both parties' IP addresses during a call, like any VoIP server. In production they belong
  behind the same operator as the API.
- The server keeps call invites for a day: who called whom, when, and the outcome. Shorter retention would break
  "missed call" for a phone that was off; a day is the trade-off.
- A UnifiedPush endpoint's host is resolved again before each push, but a DNS answer can still change between the
  check and the connection (DNS rebinding). Pinning the resolved address for the request would close it.
- ffmpeg parses untrusted uploads. Its CVEs matter: the transcoder image follows Ubuntu's security updates and
  runs isolated, but it should be rebuilt regularly.
- Hidden clips have no admin review screen yet; a hidden clip stays hidden until its owner records a new one.
- The callee gets the caller's clip only when it downloads in time (Wi-Fi by default); there is no retry during
  the call.
