# OpenCaller agents

LiveKit agents for the recording line (docs/prd.md F-UT-03, ADR 0009). Setup of LiveKit SIP, the trunk, the
dispatch rule and Egress: `infra/deploy/telephony.md`.

| Agent | Run | What it does |
| --- | --- | --- |
| `recorder` | `python -m opencaller_agents.recorder start` | Dispatched by the recording line's dispatch rule to each call: plays a ready signal, reads the one-time code (DTMF), claims the session at the server, starts a LiveKit track egress of the caller's audio to MinIO, plays the beep (and the optional announcement) for both parties, beeps while recording, stops the egress when the call ends and reports the file. |
| `transcriber` | `python -m opencaller_agents.transcriber` | Only for users who turned transcripts on: takes waiting recordings from the server, transcribes them with self-hosted Whisper (faster-whisper, CPU) and sends the text back. |

Neither agent sees or logs a phone number: the dispatch rule hides the caller's number, the room is named after the
line, and the code alone links a call to an account.

## Settings

| Variable | Needed by | Meaning |
| --- | --- | --- |
| `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` | recorder | The LiveKit server (the agents framework reads them) |
| `OPENCALLER_API_URL` | both | The OpenCaller server, e.g. `http://127.0.0.1:8080` |
| `RECORDING_AGENT_TOKEN` | both | The server's `RECORDING_AGENT_TOKEN` (32+ characters) |
| `RECORDING_S3_ENDPOINT`, `RECORDING_S3_BUCKET`, `RECORDING_S3_REGION`, `RECORDING_S3_ACCESS_KEY`, `RECORDING_S3_SECRET_KEY` | recorder | Where Egress uploads (MinIO as the egress service reaches it) |
| `RECORDING_ANNOUNCEMENT_WAV` | recorder | Optional 16-bit PCM WAV played after the beep (any rate; mixed to mono, resampled to 48 kHz) |
| `WHISPER_MODEL` | transcriber | A faster-whisper model name (default `small`, downloaded from Hugging Face on first use) or folder |

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
