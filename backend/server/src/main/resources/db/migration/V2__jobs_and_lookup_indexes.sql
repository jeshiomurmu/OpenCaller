-- Phase 4 part 2 (docs/architecture.md §7, "Later migrations"). V1 stays identical to §7.

-- Progress of background jobs, e.g. the newest report the scoring job has seen.
CREATE TABLE job_state (
  name TEXT PRIMARY KEY, watermark TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now());

-- Scoring: reports received since the last run, and scores due for a time-decay refresh.
CREATE INDEX ON reports (created_at);
CREATE INDEX ON number_reputation (updated_at);
-- List builder: numbers at or above the list threshold.
CREATE INDEX ON number_reputation (spam_score);
-- Names are hidden for numbers with a processed unlist request.
CREATE INDEX ON unlist_requests (e164);
-- One open automatic dispute per number.
CREATE INDEX ON disputes (e164, status);
-- "Who searched me" (Phase 8) and retention clean-up.
CREATE INDEX ON profile_views (viewed_user_id, at);
