-- Phase 11 (ADR 0007): push endpoints, app calls, Flash messages and video caller IDs.

-- Push endpoints live on the device row (V1 has push_type and push_token): FCM keeps its registration token in
-- push_token; Web Push (UnifiedPush) keeps the endpoint URL there and the subscription's keys below.
ALTER TABLE devices
  ADD COLUMN push_p256dh TEXT,
  ADD COLUMN push_auth TEXT,
  ADD COLUMN push_updated_at TIMESTAMPTZ,
  ADD CONSTRAINT devices_push_type CHECK (push_type IS NULL OR push_type IN ('fcm', 'webpush'));

-- Whether app calls and Flash messages may reach the user (on by default; a setting turns it off).
ALTER TABLE users ADD COLUMN reachable BOOLEAN NOT NULL DEFAULT TRUE;

-- What a push stands for. A push carries only this row's random id; the app fetches the item to learn what it is.
CREATE TABLE push_items (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK (kind IN ('call_invite', 'call_update', 'flash')),
  ref_id UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX push_items_user ON push_items (user_id, created_at DESC);
CREATE INDEX push_items_expiry ON push_items (expires_at);

-- App calls. The LiveKit room is the sorted pair of user IDs plus a random nonce. Rows are deleted a day after
-- the call (the cleanup job), so the server keeps no call history.
CREATE TABLE call_invites (
  id UUID PRIMARY KEY,
  caller_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  callee_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  room TEXT NOT NULL UNIQUE,
  state TEXT NOT NULL CHECK (state IN ('ringing', 'accepted', 'declined', 'cancelled', 'missed', 'ended')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  ring_until TIMESTAMPTZ NOT NULL,
  answered_at TIMESTAMPTZ,
  ended_at TIMESTAMPTZ
);
CREATE INDEX call_invites_callee ON call_invites (callee_id, created_at DESC);
CREATE INDEX call_invites_caller ON call_invites (caller_id, created_at DESC);
CREATE INDEX call_invites_created ON call_invites (created_at);

-- Flash messages wait here until the recipient's phone fetches them (then the row is deleted) or for a day.
CREATE TABLE flash_messages (
  id UUID PRIMARY KEY,
  sender_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  recipient_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK (kind IN ('call_me', 'on_my_way', 'running_late', 'cant_talk', 'arrived', 'call_you_back')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX flash_messages_expiry ON flash_messages (expires_at);

-- One video caller ID per user. video_id/video_key are the clip people see (null until the first one is ready);
-- upload_key/job_state are a new upload on its way through the transcoder. hidden = taken down after reports.
-- (V1's users.video_caller_id_key is unused.)
CREATE TABLE video_caller_ids (
  user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  video_id UUID UNIQUE,
  video_key TEXT,
  video_bytes INTEGER,
  upload_key TEXT,
  job_state TEXT NOT NULL DEFAULT 'none' CHECK (job_state IN ('none', 'uploading', 'queued', 'processing', 'failed')),
  job_error TEXT,
  claimed_at TIMESTAMPTZ,
  hidden BOOLEAN NOT NULL DEFAULT FALSE,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX video_caller_ids_queue ON video_caller_ids (updated_at) WHERE job_state = 'queued';

-- Reports of a clip; three different reporters hide it until it is reviewed.
CREATE TABLE video_reports (
  video_id UUID NOT NULL,
  reporter_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (video_id, reporter_id)
);
