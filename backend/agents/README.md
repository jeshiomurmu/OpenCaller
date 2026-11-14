# OpenCaller agents

LiveKit agents for the recording line (docs/prd.md F-UT-03, ADR 0009), the call assistant (F-UT-04, ADR 0010) and the
voice scanner (F-PR-13, ADR 0011). Setup of LiveKit SIP, the trunk, the
dispatch rule and Egress: `infra/deploy/telephony.md`.

| Agent | Run | What it does |
| --- | --- | --- |
| `recorder` | `python -m opencaller_agents.recorder start` | Dispatched by the recording line's dispatch rule to each call: plays a ready signal, reads the one-time code (DTMF), claims the session at the server, starts a LiveKit track egress of the caller's audio to MinIO, plays the beep (and the optional announcement) for both parties, beeps while recording, stops the egress when the call ends and reports the file. |
| `transcriber` | `python -m opencaller_agents.transcriber` | Only for users who turned transcripts on: takes waiting recordings from the server, transcribes them with self-hosted Whisper (faster-whisper, CPU) and sends the text back. |
| `assistant` | `python -m opencaller_agents.assistant start` | Answers calls forwarded to a user's assistant number: a fixed disclosure, then speech-to-text → language model → text-to-speech with the operator's providers; the caller's number and the conversation go to the user's phone only (LiveKit data messages). |
| `scanner` | `python -m opencaller_agents.scanner start` | Dispatched to line calls next to the recorder: reads the code and claims a voice scan session (the recorder leaves a scan's call, and the scanner a recording's), beeps for both parties, keeps up to two 4 s windows of the other party's speech at 16 kHz, scores them with `ml/voice-spoof`, transcribes them with self-hosted Whisper to find scam-cue phrases (en/bn/hi, `scam_cues.py`) and reports only the probability and the cue ids; then it hangs up the line. Audio and text stay in memory. |

The recorder, transcriber and scanner never see or log a phone number: the dispatch rule hides the caller's number,
the room is named after the line, and the code alone links a call to an account. The assistant reads the caller's
number from LiveKit to show it on the user's phone and never sends it to the server (ADR 0010).

## Settings

| Variable | Needed by | Meaning |
| --- | --- | --- |
| `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` | recorder | The LiveKit server (the agents framework reads them) |
| `OPENCALLER_API_URL` | both | The OpenCaller server, e.g. `http://127.0.0.1:8080` |
| `RECORDING_AGENT_TOKEN` | both | The server's `RECORDING_AGENT_TOKEN` (32+ characters) |
| `RECORDING_S3_ENDPOINT`, `RECORDING_S3_BUCKET`, `RECORDING_S3_REGION`, `RECORDING_S3_ACCESS_KEY`, `RECORDING_S3_SECRET_KEY` | recorder | Where Egress uploads (MinIO as the egress service reaches it) |
| `RECORDING_ANNOUNCEMENT_WAV` | recorder | Optional 16-bit PCM WAV played after the beep (any rate; mixed to mono, resampled to 48 kHz) |
| `WHISPER_MODEL` | transcriber | A faster-whisper model name (default `small`, downloaded from Hugging Face on first use) or folder |
| `VOICE_SPOOF_URL`, `VOICE_SPOOF_TOKEN` | scanner | The voice-spoof service (`ml/voice-spoof`) and its bearer token (32+ characters) |
| `SCANNER_WHISPER_MODEL` | scanner | faster-whisper model for the scam cues (default `small`), or `off` for the voice result only |
| `SCANNER_WINDOWS` | scanner | How many 4 s windows of speech are scored (1–5, default 2; the mean is reported) |

Each agent refuses to start and lists every missing or invalid variable.

## Tests

```shell
python3 -m venv .venv
.venv/bin/pip install -e '.[test]'
.venv/bin/ruff check src tests && .venv/bin/ruff format --check src tests
.venv/bin/pytest -q
```

The tests use fakes for the server, LiveKit's egress API and the room's audio; no LiveKit server is needed. A real
call is TC-REC-001 (docs/testing.md), on a real SIM.
