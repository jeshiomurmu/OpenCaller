# ADR 0010 — The AI call-screening assistant

- Status: Accepted
- Date: 2026-11-11
- Phase 13b: the AI assistant (F-UT-04, TC-AST-001); the recording line is ADR 0009, the voice scanner follows

## Context

F-UT-04 lets an automated assistant answer the calls a user misses: it says it is an assistant, asks who is calling
and why, and the user follows the conversation live on their phone and decides (call back, send a short reply, or
end it as spam). Android gives an app no way to answer a call with synthetic audio, so the call has to leave the
phone: the user sets **conditional call forwarding** (no answer, unreachable, busy) on their SIM to a number the
server answers through LiveKit SIP, where a voice agent (speech-to-text → language model → text-to-speech) talks to
the caller.

This puts a stranger's voice, their number and what they say through our servers and through the speech and
language providers the operator picks. The design has to know whose call it is without trusting carrier headers,
keep the caller's data off the server, and cap the cost.

Owner decisions of 2026-11-11:

- a forwarded call is matched to the user by a **dedicated assistant number per user**, from a pool the operator
  buys. LiveKit gives the agent the dialed number (`sip.trunkPhoneNumber`); the forwarding `Diversion` header can't
  be relied on (carriers set it differently, and LiveKit maps only `X-` headers to attributes);
- **all four provider families** are selectable by environment: OpenAI-compatible endpoints (OpenAI or the
  operator's own servers), Deepgram, ElevenLabs and Google;
- the caller's number, the transcript and the summary go **to the user's phone only**, through the call's LiveKit
  room; the server keeps the minutes used per month and a short-lived call row with no phone number;
- a **monthly cap**, `ASSISTANT_MONTHLY_MINUTES` (default 100, 0 = no cap), plus a per-call limit
  (`ASSISTANT_MAX_CALL_MINUTES`, default 5).

## Decision

**Numbers.** `ASSISTANT_NUMBERS` lists the pool; the server keeps it in `assistant_numbers` (synced at start: new
numbers added, removed ones disabled and taken from their user). `POST /v1/lines/assistant` gives a supporter (or
anyone on a self-hosted server) a free number of the country of their verified number, the same one on every call;
`DELETE` gives it back. A number belongs to one user at a time (unique index; `FOR UPDATE SKIP LOCKED` when picking).

**Forwarding.** The app shows the standard GSM codes for the user's number — `**61*`, `**62*`, `**67*` + number +
`#`, and `##002#` to cancel all — and opens them in the dial pad, so the user presses Call and sees the network's
answer. It never dials them silently. Carriers that don't accept the codes (some prepaid plans and VoLTE networks)
get guidance to use the phone's call settings or the carrier's app, and a warning that forwarded calls may cost.

**Call flow.** Twilio (or another trunk) → LiveKit SIP inbound trunk for the assistant numbers → a callee dispatch
rule with `room_prefix: "ast"`, `randomize: true` and the `assistant` agent. Unlike the recording line's rule it does
**not** hide the caller's number: the agent needs it for the user's screen. The agent claims the call with
`POST /agent/v1/assistant/calls {number, room}` (bearer `RECORDING_AGENT_TOKEN`) and gets the call id, the user's
LiveKit identity and the seconds it may talk; the server sends the user's phone an `assistant_call` push carrying only
the item's id (ADR 0007). An unknown number (404), a lapsed subscription (403) or a used-up month (429) make the
agent say the person can't take calls and hang up. At the end the agent reports the duration
(`/end`, clamped to the time since the claim).

**The agent** (`backend/agents`, `opencaller_agents.assistant`, livekit-agents 1.8.11): `AgentSession` with the
providers from `ASSISTANT_STT`, `ASSISTANT_LLM`, `ASSISTANT_TTS` (+ `_MODEL`, `_VOICE`, `_BASE_URL`) and Silero VAD.
The greeting and the closing sentences are fixed text (the disclosure that it is an automated assistant must not
depend on the model). The prompt asks for the name and the reason, one short question at a time; forbids asking for
or repeating one-time codes, passwords, PINs, card or bank details; forbids promising a call back or revealing
anything about the user; and asks the model to call `record_details(name, reason, urgency, likely_spam)` and
`end_call` after a polite goodbye. It starts saying goodbye 20 s before the allowed time runs out.

**The live screen.** The app joins the room with a LiveKit token that can't publish any track and doesn't subscribe
to audio; only data messages flow, on topic `opencaller.assistant` (JSON, version 1). Agent → app, sent with
`destination_identities` = the user's identity only: `hello` (caller's number or hidden), `partial`, `line`,
`details`, `ended`; an app that joins late gets everything again. App → agent, accepted only from that identity:
`reply` (≤ 200 characters, spoken prefixed "Here is a message from the person you called"), `callback` (the agent
says the user will call back and hangs up; the app opens the dial pad with the caller's number), `spam` (the agent
ends the call). F-Droid builds have no LiveKit, so their live screen and setup say the assistant isn't available.

**Storage.** Server: `assistant_numbers`, `assistant_calls` (user, room, start, end, duration; deleted a day after
the call by `voip-cleanup`), `assistant_usage` (user, month, seconds). Phone: `screened_call` (Room v6: caller,
name, reason, urgency, spam flag, transcript, how it ended), kept 90 days, never backed up to the server.

## Consequences

- The speech and language providers hear the caller and see the transcript; that is the operator's choice and must
  be in their privacy policy (Data safety lists it). An operator who wants none of that runs OpenAI-compatible
  servers of their own (`*_BASE_URL`).
- LiveKit's SIP participant carries the caller's number in its attributes while the call lasts (the agent reads it
  from there); LiveKit logs are the operator's to configure.
- The fixed sentences are English for now; the model answers in the caller's language. Translations of the fixed
  sentences, and the cost per screened minute (the exit criterion), are measured on a real SIM (TC-AST-001).
- Conditional forwarding depends on the carrier and may be charged; the app can't check that it was set, only show
  the network's answer to the code.
- One number per user means the pool has to grow with the users; when a country's pool is empty, new users there get
  `assistant_unavailable` until the operator adds numbers.
