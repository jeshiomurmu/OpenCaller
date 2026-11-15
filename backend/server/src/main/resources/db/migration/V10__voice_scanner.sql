-- Phase 13: the voice scanner (docs/prd.md F-PR-13, ADR 0011). A scan uses the recording line: the same numbers and
-- one-time codes, with the session's kind telling the recorder and the scanner agent which of them takes the call.
-- A scan keeps only its outcome, the spoof probability and the scam-cue ids found in the transcript (never the audio
-- or the text), and the session row goes a day later (recording-retention job).
ALTER TABLE recording_sessions ADD COLUMN kind TEXT NOT NULL DEFAULT 'record' CHECK (kind IN ('record', 'scan'));
ALTER TABLE recording_sessions ADD COLUMN scan_probability REAL CHECK (scan_probability >= 0 AND scan_probability <= 1);
ALTER TABLE recording_sessions ADD COLUMN scan_cues TEXT;
