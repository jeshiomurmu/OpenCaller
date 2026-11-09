-- Phase 12 (ADR 0008): family protection, the scam feed and the business dashboard.

-- Family groups (V1) get a name and a creation time; each admin runs at most one group.
ALTER TABLE family_groups
  ADD COLUMN name TEXT NOT NULL DEFAULT 'Family',
  ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE family_groups ALTER COLUMN owner_id SET NOT NULL;
CREATE UNIQUE INDEX family_groups_owner ON family_groups (owner_id);

-- A member row is written only by the member's own request (the admin's when creating the group, everyone else's
-- when joining with an invite code): consented_at (V1) is when they agreed, consent_version which wording they saw,
-- and the two switches what they chose to share. A person is in at most one group.
ALTER TABLE family_members
  ADD COLUMN label TEXT NOT NULL,
  ADD COLUMN consent_version INT NOT NULL,
  ADD COLUMN share_otp_alerts BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN share_daily_counts BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  ADD CONSTRAINT family_members_role CHECK (role IN ('admin', 'member'));
CREATE UNIQUE INDEX family_members_user ON family_members (user_id);

-- Invite codes: single use, 48 hours; only the SHA-256 of the code is kept.
CREATE TABLE family_invites (
  code_hash TEXT PRIMARY KEY,
  group_id UUID NOT NULL REFERENCES family_groups(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  used_at TIMESTAMPTZ
);
CREATE INDEX family_invites_group ON family_invites (group_id);

-- Alerts a member's phone raised for the admin: the kind and the time, nothing else (no number, no message text).
CREATE TABLE family_alerts (
  id UUID PRIMARY KEY,
  group_id UUID NOT NULL REFERENCES family_groups(id) ON DELETE CASCADE,
  member_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK (kind IN ('otp_during_call', 'otp_burst')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX family_alerts_group ON family_alerts (group_id, created_at DESC);
CREATE INDEX family_alerts_created ON family_alerts (created_at);

-- A member's daily counts for the admin's summary: calls blocked and messages filtered as spam or scam that day.
CREATE TABLE family_daily_counts (
  member_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  day DATE NOT NULL,
  blocked_calls INT NOT NULL CHECK (blocked_calls BETWEEN 0 AND 10000),
  spam_messages INT NOT NULL CHECK (spam_messages BETWEEN 0 AND 10000),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (member_id, day)
);
CREATE INDEX family_daily_counts_day ON family_daily_counts (day);

-- Family alerts reach the admin like app calls and Flash messages: a push carrying only the item's random id.
ALTER TABLE push_items DROP CONSTRAINT push_items_kind_check;
ALTER TABLE push_items ADD CONSTRAINT push_items_kind_check CHECK (kind IN ('call_invite', 'call_update', 'flash', 'family_alert'));

-- Scam feed: cards curated in the admin web, per country and language.
CREATE TABLE scam_cards (
  id UUID PRIMARY KEY,
  country_iso CHAR(2) NOT NULL,
  language TEXT NOT NULL,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  link TEXT,
  published BOOLEAN NOT NULL DEFAULT FALSE,
  expires_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX scam_cards_country ON scam_cards (country_iso, updated_at DESC);

-- Explicit searches of verified-business numbers, counted per number and day for the business dashboard. Who
-- searched is never stored here. Kept 90 days.
CREATE TABLE business_search_counts (
  e164 TEXT NOT NULL,
  day DATE NOT NULL,
  searches INT NOT NULL DEFAULT 0,
  PRIMARY KEY (e164, day)
);
CREATE INDEX business_search_counts_day ON business_search_counts (day);

-- Business dashboard sessions: only the SHA-256 of the cookie's token is kept, with the form token (CSRF).
CREATE TABLE portal_sessions (
  token_hash TEXT PRIMARY KEY,
  business_id UUID NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  e164 TEXT NOT NULL,
  csrf TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX portal_sessions_expiry ON portal_sessions (expires_at);
