# ADR 0011 — The voice scanner

- Status: Accepted
- Date: 2026-11-14
- Phase 13c: the voice scanner (F-PR-13, TC-SCAN-001); the recording line is ADR 0009, the call assistant ADR 0010

## Context

F-PR-13 lets a user check, during a call, whether the other party's voice sounds generated: a cloned voice of a
relative or a text-to-speech "bank officer". The result must read as a warning, never a verdict (PRD: "Likely human /
Likely AI shown as warning, never verdict"). Android gives an app no access to the other party's audio, so the check
runs where the recording line already brings the call: the user merges the call with the server's line, and an agent
listens there.

This puts a stranger's voice through our servers for a few seconds. The design has to avoid a second set of phone
numbers, keep the audio and what was said off disk, and be honest about how well the model works on a phone line.

Owner decisions of 2026-11-14:

- **Share the recording lines.** The same numbers and the same one-time code; the session has a kind (`record` or
  `scan`). The dispatch rule sends both agents to every line call; the claimed code decides which one stays.
- **A beep, in the same countries.** A short beep both parties hear; scanning is offered only where recording is
  approved (`RECORDING_COUNTRIES` and the admin web).
- **Scam cues in English, Bengali and Hindi.** The agent transcribes the sampled seconds with self-hosted Whisper;
  nothing is stored; only cue ids reach the phone, through the server. The Bengali and Hindi lists need a native
  speaker's review (`docs/phase.md`, open issues).
- **Evaluation data:** ASVspoof 2019 LA (University of Edinburgh, ODC-By 1.0), a stratified subset of its evaluation
  set, clean and through telephone codecs.

## Decision

**Model.** AASIST (Jung et al., 2022; `clovaai/aasist`, MIT, NAVER), pretrained on ASVspoof 2019 LA. The MIT licence
allows redistribution, but the repository carries no weights: `ml/voice-spoof`'s image downloads `AASIST.pth` from the
authors' repository at build time, checks its pinned SHA-256 and exports it to ONNX with PyTorch's CPU build; the
service runs the ONNX model with onnxruntime (no PyTorch at run time). Input: 4 s of 16 kHz mono 16-bit PCM
(`POST /v1/score`, bearer `VOICE_SPOOF_TOKEN`, internal only); output: the softmax of the spoof class. The score is not
calibrated; 0.5 is where the two classes meet. Equal error rates on the evaluation subset, clean and through G.711,
GSM-FR and G.711 → Opus, are in `ml/voice-spoof/README.md`.

**Sessions.** V10 adds `kind` (`record` | `scan`), `scan_probability` and `scan_cues` to `recording_sessions`.
`POST /v1/lines/scanner` (supporter or self-hosted, `SCANNER_ENABLED`, an approved country with a line) returns the
line and a code like recording does; `GET /v1/lines/scanner/{id}` the state and, once ended, the probability and cue
ids. The agents' claim (`/agent/v1/recording/claim`) carries `kind`; a code of the other kind answers `409
other_kind` and stays pending, so whichever agent reads the code first can't take the other's session. The scanner
reports `{status, spoofProbability, cues}` to `/agent/v1/scanner/sessions/{id}/result`; unknown cue ids and
probabilities outside 0–1 are refused. Scan rows are deleted a day after they were created (`recording-retention`).

**Call flow.** The user taps Scan voice and confirms the explanation → `feature:scanner` (`LineScanner`, on the shared
`LineConnection` of `core:telephony`) dials `line,,code#` → both the `recorder` and the `scanner` agent join the room
and read the code; the recorder plays the ready tones; the recorder's claim gets `other_kind` and it leaves without a
sound and without hanging up; the scanner claims → the app merges the calls and **mutes the user's microphone**, so
the line hears only the other party → after 4 s the scanner beeps (both parties hear it) and reads the SIP
participant's audio at 16 kHz (`rtc.AudioStream.from_track`) → it keeps up to two 4 s windows that are at least 40 %
voiced (it listens at most 40 s) and scores them (the mean is reported) → it transcribes the same seconds with
faster-whisper on the CPU (language detected) and matches the cue phrases → it reports and deletes the room, which
hangs up the line; the app unmutes (unless the user had muted) and shows the result. The user's call goes on.

**The result.** "Likely human" below 0.5, "Possibly AI-generated" from 0.5, always with the sentence that the check
can be wrong both ways and what to do instead (hang up, call back on a known number), and "They mentioned: …" for the
cues. "The scanner couldn't check this voice" when it heard too little or failed. Nothing is saved on the phone.

## Consequences

- Measured on the evaluation subset (README): 0.78 % EER clean, 1.09 % through G.711, 1.30 % through this stack's
  G.711 → Opus path, but 6.61 % through GSM-FR, where the fixed 0.5 flags 31.8 % of real voices (11.9 % even at
  0.99). The threshold stays at 0.5, which suits the stack's own path; callers on GSM networks will see false
  "Possibly AI-generated" results until the model is fine-tuned on codec-augmented data (open issue 166).
- AASIST was trained on 2019 text-to-speech and voice-conversion systems (A07–A19). Current voice cloning is likely
  to be missed more often than the evaluation suggests, and phone lines raise the error rate (README). The wording
  says so; the result is never used to block or report a number.
- Merging needs the default phone app and a network that offers conferences, as recording does (issue 150). A scan
  uses up to a minute of the line; it costs the operator a line call (Twilio's per-minute price).
- The voice passes through LiveKit and the scanner on the operator's machines; audio and transcript stay in the
  agent's memory and are dropped after the scan. The server keeps a probability and cue ids for a day.
- Cue matching is phrase lists, not understanding: it misses paraphrases and can fire on innocent mentions (a real
  police officer). The Bengali and Hindi phrases await a native speaker's review.
- Whisper downloads its model from Hugging Face on first start (as for transcripts, issue 153); `SCANNER_WHISPER_MODEL=off`
  turns the cues off and keeps the voice result.
