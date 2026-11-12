-- Phase 13: the AI call-screening assistant (docs/prd.md F-UT-04, ADR 0010).
-- A user forwards unanswered calls to an assistant number of their own; the agent answers, and the app follows the
-- call through LiveKit. The caller's number and the transcript go from the agent to the user's phone only: the
-- server keeps the number pool, a short-lived row per call (user and room, no phone number) and minutes per month.

-- The operator's pool (ASSISTANT_NUMBERS, synced at start). A number belongs to at most one user; a number the
-- operator removes from the pool is kept with enabled = false so its user gets a new one on the next request.
CREATE TABLE assistant_numbers (
  e164 TEXT PRIMARY KEY CHECK (e164 ~ '^\+[1-9][0-9]{6,14}$'),
  country_iso CHAR(2) NOT NULL CHECK (country_iso ~ '^[A-Z]{2}$'),
  enabled BOOLEAN NOT NULL DEFAULT true,
  user_id UUID UNIQUE REFERENCES users(id) ON DELETE SET NULL,
  assigned_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX assistant_numbers_free ON assistant_numbers (country_iso) WHERE user_id IS NULL AND enabled;

-- One forwarded call the agent answered: whose it is and which LiveKit room, so the user's app can join it.
-- Deleted a day after it started (voip-cleanup).
CREATE TABLE assistant_calls (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  room TEXT NOT NULL,
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  ended_at TIMESTAMPTZ,
  duration_seconds INT CHECK (duration_seconds >= 0)
);
CREATE INDEX assistant_calls_started ON assistant_calls (started_at);

-- Seconds the assistant spent on a user's calls in a calendar month (UTC), for the cap and the app's meter.
CREATE TABLE assistant_usage (
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  month DATE NOT NULL CHECK (EXTRACT(DAY FROM month) = 1),
  seconds INT NOT NULL DEFAULT 0 CHECK (seconds >= 0),
  PRIMARY KEY (user_id, month)
);

-- A call the agent answers reaches the app like the other items: a push carrying only the item's random id.
ALTER TABLE push_items DROP CONSTRAINT push_items_kind_check;
ALTER TABLE push_items ADD CONSTRAINT push_items_kind_check
  CHECK (kind IN ('call_invite', 'call_update', 'flash', 'family_alert', 'recording_ready', 'assistant_call'));
