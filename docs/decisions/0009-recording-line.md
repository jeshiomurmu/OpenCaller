# ADR 0009 — The recording line

- Status: Accepted
- Date: 2026-11-10
- Phase 13: the recording line (F-UT-03); the AI assistant and the voice scanner follow (`docs/implementation_plan.md`)

## Context

Android has no supported way for an app to record the audio of a phone call (rules.md §2 forbids the Accessibility
API and hidden APIs for this). F-UT-03 therefore records through the network: the user merges their call with a
server phone number, and the server records the merged call. That puts audio of two people, one of whom never
installed OpenCaller, on our servers, and call recording law differs from country to country. The design has to keep
that audio there as briefly as possible, make sure both parties are told, and let the operator decide where it is
offered at all.

Owner decisions of 2026-11-10:

- the SIP trunk provider is **Twilio** (Elastic SIP Trunking; LiveKit's own Twilio quickstart);
- recording is offered at launch in **Bangladesh and India** (`RECORDING_COUNTRIES=BD,IN`); every other country is off,
  listed only as a commented placeholder that must be approved after the operator's own legal review; self-hosters
  set their own list;
- the **server's job** deletes recordings (not the agent): within an hour after the app confirms the download, after
  24 hours at the latest;
- optional transcripts use **self-hosted Whisper**; summaries come later.

## Decision

### The call path

- One phone number per country, connected through a SIP trunk to a self-hosted LiveKit SIP server
  (`infra/deploy/telephony.md`). An inbound trunk accepts calls to the numbers; a **callee** dispatch rule with
  `randomize: true` puts each call into its own room named after the line (`rec_<line>_<random>`), with
  `hide_phone_number: true`, and dispatches the `recorder` agent. The caller's number never appears in a room name,
  a participant identity or the agent's logs.
- `POST /v1/lines/recording` gives a supporter (or anyone on a self-hosted server) in an approved country that
  country's line and a one-time 8-digit code, valid 10 minutes, single use, kept only as an HMAC with the server's
  pepper. The app dials `line,,code#` (two pauses, then tones); the agent reads the code and claims the session
  (`/agent/v1/recording/claim`, bearer `RECORDING_AGENT_TOKEN`). The code, not caller ID, links the call to the
  account: caller ID is spoofable and often missing. A call gets 3 tries; the server allows 600 claims per 10 minutes.
- The agent plays a ready signal, then — once the session is claimed and the app has merged the calls (`Call.conference`)
  — a beep and the operator's announcement (an optional WAV) that both parties hear, and a short beep every 15 seconds.
  It records the SIP participant's audio track with LiveKit track egress (Opus in OGG, written straight to MinIO at
  `recordings/<session>.ogg`; no browser, little CPU) and stops it when the call ends or after 3 hours.
- The agent reports the result; the server stores the size MinIO reports (not the agent's) and pushes
  `recording_ready`, which carries only a random item id like every push (ADR 0007).

### What the server keeps, and for how long

- `recording_sessions`: user, line, code HMAC, state and times; deleted after two days.
- `recordings`: object key, size, duration, transcript state and text (only if the user turned transcripts on);
  never the other party's or the user's number. The phone keeps the label (who the call was with) locally.
- The `recording-retention` job runs every 10 minutes: a recording is deleted (file first, then the row)
  `RECORDING_DOWNLOAD_GRACE_MINUTES` (default 30, at most 50) after the app confirmed the download — once a requested
  transcript is done — and every recording 24 hours after it was made at the latest. It also removes the file of a
  session whose agent never reported and gives up transcripts that hang for an hour.
- Deleting a recording in the app deletes it from the server at once.

### The legal switch

- `RECORDING_COUNTRIES` (default `BD,IN`) lists where recording is approved; the admin web overrides single countries
  (switching one on needs the admin to confirm that the legal review approved it) and writes the audit log.
- The country is the one of the user's own verified number. The app's consent reminder, before every recording, says
  that both parties hear the beep and announcement and that the user is responsible for the law where the other party
  is. In places that require every party's consent a beep may not be enough; that is why every other country stays
  off until reviewed.

### Transcripts

- Off by default; `PUT /v1/recordings/settings`. Only recordings started while it is on get a transcript.
- A separate `transcriber` worker on the operator's machine takes waiting recordings, runs faster-whisper (CTranslate2,
  CPU) and posts the text. The audio is fetched through a 30-minute presigned link and never leaves the operator's
  servers. Summaries (an LLM) are not part of this phase.

## Consequences

- Recording works only when OpenCaller is the default phone app (it places the call to the line and merges) and the
  network supports merging calls (most GSM/VoLTE networks; some prepaid plans don't). The app says so when the merge
  fails; the user's call goes on.
- The other party hears the beep and the announcement; that is the point, and some users will dislike it.
- The operator needs a SIP trunk, phone numbers and their own legal review for each country. Without lines and a
  token the feature is off.
- Signalling and media between Twilio and the SIP server are unencrypted until secure trunking (TLS + SRTP) is
  configured; the guide describes it but the self-hosted SIP server's TLS listener isn't set up yet (open issue).
- The default stack has no spoken announcement: the operator records one per language (`RECORDING_ANNOUNCEMENT_WAV`);
  the beep always plays.
- Transcripts on a CPU take roughly as long as the call with the `small` model; a busy server falls behind, and the
  deletion of a downloaded recording waits for its transcript (still never past 24 hours).
