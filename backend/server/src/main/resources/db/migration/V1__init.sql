-- OpenCaller server schema v1. Copied verbatim from docs/architecture.md §7 (ServerSchemaTest checks they match).
CREATE TYPE visibility AS ENUM ('everyone', 'registered_only', 'nobody');
CREATE TYPE report_label AS ENUM ('spam', 'not_spam');
CREATE TYPE review_status AS ENUM ('pending', 'approved', 'rejected');

CREATE TABLE users (
  id UUID PRIMARY KEY, e164 TEXT UNIQUE NOT NULL, verified_at TIMESTAMPTZ NOT NULL,
  display_name TEXT, avatar_key TEXT, video_caller_id_key TEXT,
  visibility visibility NOT NULL DEFAULT 'registered_only', incognito BOOLEAN NOT NULL DEFAULT false,
  supporter_tier TEXT NOT NULL DEFAULT 'none', created_at TIMESTAMPTZ NOT NULL DEFAULT now(), deleted_at TIMESTAMPTZ);

CREATE TABLE devices (
  id UUID PRIMARY KEY, user_id UUID REFERENCES users(id) ON DELETE CASCADE, flavor TEXT NOT NULL,
  push_type TEXT, push_token TEXT, integrity_level SMALLINT NOT NULL DEFAULT 0, last_seen TIMESTAMPTZ);

CREATE TABLE refresh_tokens (
  token_hash TEXT PRIMARY KEY, device_id UUID REFERENCES devices(id) ON DELETE CASCADE,
  expires_at TIMESTAMPTZ NOT NULL, rotated_at TIMESTAMPTZ);

CREATE TABLE businesses (
  id UUID PRIMARY KEY, legal_name TEXT NOT NULL, display_name TEXT NOT NULL, logo_key TEXT,
  category TEXT, website TEXT, country_iso CHAR(2) NOT NULL, status review_status NOT NULL DEFAULT 'pending',
  verified_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now());

CREATE TABLE business_numbers (
  e164 TEXT PRIMARY KEY, business_id UUID REFERENCES businesses(id) ON DELETE CASCADE,
  default_call_reason TEXT, claimed_via TEXT NOT NULL, claimed_at TIMESTAMPTZ NOT NULL DEFAULT now());

CREATE TABLE sender_ids (
  country_iso CHAR(2) NOT NULL, header TEXT NOT NULL, brand_name TEXT NOT NULL, category TEXT NOT NULL,
  source TEXT NOT NULL, business_id UUID REFERENCES businesses(id), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (country_iso, header));

CREATE TABLE number_reputation (
  e164 TEXT PRIMARY KEY, country_code SMALLINT NOT NULL, bucket_16 INTEGER NOT NULL,
  spam_score REAL NOT NULL DEFAULT 0, category TEXT, report_count INTEGER NOT NULL DEFAULT 0,
  not_spam_count INTEGER NOT NULL DEFAULT 0, line_type TEXT, first_seen TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_report_at TIMESTAMPTZ, model_version TEXT, updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX ON number_reputation (country_code, bucket_16);

CREATE TABLE reports (
  id BIGSERIAL PRIMARY KEY, e164 TEXT NOT NULL, reporter_id UUID REFERENCES users(id) ON DELETE SET NULL,
  label report_label NOT NULL, category TEXT, comment TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX ON reports (e164, created_at);

CREATE TABLE reporter_trust (user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  trust REAL NOT NULL DEFAULT 0.5, updated_at TIMESTAMPTZ NOT NULL DEFAULT now());

CREATE TABLE honeypot_hits (id BIGSERIAL PRIMARY KEY, e164 TEXT NOT NULL, honeypot TEXT NOT NULL, at TIMESTAMPTZ NOT NULL);

CREATE TABLE disputes (
  id BIGSERIAL PRIMARY KEY, e164 TEXT NOT NULL, claimant_user_id UUID REFERENCES users(id),
  reason TEXT NOT NULL, status review_status NOT NULL DEFAULT 'pending', reviewer TEXT, resolved_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now());

CREATE TABLE unlist_requests (
  id BIGSERIAL PRIMARY KEY, e164 TEXT NOT NULL, verified_at TIMESTAMPTZ, processed_at TIMESTAMPTZ);

CREATE TABLE profile_views (
  id BIGSERIAL PRIMARY KEY, viewed_user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  viewer_user_id UUID REFERENCES users(id) ON DELETE CASCADE, kind TEXT NOT NULL /* view | search */,
  at TIMESTAMPTZ NOT NULL DEFAULT now());

CREATE TABLE contact_requests (
  id BIGSERIAL PRIMARY KEY, requester_id UUID REFERENCES users(id) ON DELETE CASCADE,
  target_id UUID REFERENCES users(id) ON DELETE CASCADE, status review_status NOT NULL DEFAULT 'pending',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now());

CREATE TABLE subscriptions (
  user_id UUID REFERENCES users(id) ON DELETE CASCADE, platform TEXT NOT NULL, product_id TEXT NOT NULL,
  purchase_token_hash TEXT NOT NULL, status TEXT NOT NULL, expires_at TIMESTAMPTZ, PRIMARY KEY (user_id, platform));

CREATE TABLE family_groups (id UUID PRIMARY KEY, owner_id UUID REFERENCES users(id) ON DELETE CASCADE);
CREATE TABLE family_members (group_id UUID REFERENCES family_groups(id) ON DELETE CASCADE,
  user_id UUID REFERENCES users(id) ON DELETE CASCADE, role TEXT NOT NULL, consented_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (group_id, user_id));

CREATE TABLE list_versions (country_iso CHAR(2), version BIGINT, kind TEXT /* full | delta | extended */,
  object_key TEXT NOT NULL, sha256 TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (country_iso, version, kind));
