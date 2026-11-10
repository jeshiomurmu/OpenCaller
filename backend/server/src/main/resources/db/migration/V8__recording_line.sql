-- Phase 13: the recording line (docs/prd.md F-UT-03, ADR 0009).
-- The server never stores the other party's number or the user's own number for a recording: a session is found by
-- its one-time code (kept as an HMAC), and a recording is an object key, a size, a duration and, only if the user
-- turned transcripts on, the transcript. Recordings are deleted after the app confirms the download (grace period)
-- and 24 hours after they were made at the latest (the recording-retention job).

-- The user's choice: transcripts with self-hosted Whisper (off by default).
ALTER TABLE users ADD COLUMN recording_transcripts BOOLEAN NOT NULL DEFAULT false;

-- Admin-web overrides of RECORDING_COUNTRIES: a row switches one country on or off (every change is in admin_audit).
CREATE TABLE recording_countries (
  country_iso CHAR(2) PRIMARY KEY CHECK (country_iso ~ '^[A-Z]{2}$'),
  enabled BOOLEAN NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One Record tap: the line the app dials and the code it sends. pending → active (the agent claimed it) → ended or
-- failed. Codes are unique among pending sessions.
CREATE TABLE recording_sessions (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  line_e164 TEXT NOT NULL,
  code_hash TEXT NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('pending', 'active', 'ended', 'failed')),
  transcribe BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  claimed_at TIMESTAMPTZ,
  ended_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX recording_sessions_pending_code ON recording_sessions (code_hash) WHERE state = 'pending';
CREATE INDEX recording_sessions_user ON recording_sessions (user_id, created_at DESC);
CREATE INDEX recording_sessions_created ON recording_sessions (created_at);

-- A finished recording in object storage (recordings/<session id>.ogg). user_id becomes NULL if the account is purged
-- first; the retention job still deletes the object by age.
CREATE TABLE recordings (
  id UUID PRIMARY KEY,
  session_id UUID UNIQUE REFERENCES recording_sessions(id) ON DELETE SET NULL,
  user_id UUID REFERENCES users(id) ON DELETE SET NULL,
  object_key TEXT NOT NULL UNIQUE CHECK (object_key LIKE 'recordings/%'),
  size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
  duration_ms BIGINT NOT NULL CHECK (duration_ms >= 0),
  transcript_state TEXT NOT NULL CHECK (transcript_state IN ('off', 'pending', 'working', 'done', 'failed')),
  transcript TEXT,
  transcript_started_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  downloaded_at TIMESTAMPTZ
);
CREATE INDEX recordings_user ON recordings (user_id, created_at DESC);
CREATE INDEX recordings_created ON recordings (created_at);
CREATE INDEX recordings_transcripts ON recordings (created_at) WHERE transcript_state = 'pending';

-- A finished recording reaches the app like the other items: a push carrying only the item's random id.
ALTER TABLE push_items DROP CONSTRAINT push_items_kind_check;
ALTER TABLE push_items ADD CONSTRAINT push_items_kind_check
  CHECK (kind IN ('call_invite', 'call_update', 'flash', 'family_alert', 'recording_ready'));
