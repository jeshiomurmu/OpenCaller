-- Phase 9, business claims and registries (docs/architecture.md §7, "Later migrations"). V1 stays identical to §7.

-- Business profiles (docs/prd.md F-BZ-02): opening hours, and a change time for the apps' business sync.
ALTER TABLE businesses ADD COLUMN hours TEXT;
ALTER TABLE businesses ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE business_numbers ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX ON business_numbers (updated_at);
CREATE INDEX ON business_numbers (business_id);

-- Claims of a business number (F-BZ-01): the claimant proves control of the number with a one-time code (SMS or
-- a phone call that reads it), uploads documents, and an admin approves or rejects. One open claim per number.
CREATE TABLE business_claims (
  id UUID PRIMARY KEY,
  user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  e164 TEXT NOT NULL,
  country_iso CHAR(2) NOT NULL,
  legal_name TEXT NOT NULL,
  display_name TEXT NOT NULL,
  category TEXT NOT NULL,
  website TEXT,
  hours TEXT,
  call_reason TEXT,
  logo_key TEXT,
  method TEXT NOT NULL CHECK (method IN ('sms', 'voice')),
  status TEXT NOT NULL CHECK (status IN ('verifying', 'verified', 'submitted', 'approved', 'rejected', 'withdrawn')),
  verified_at TIMESTAMPTZ,
  submitted_at TIMESTAMPTZ,
  decided_at TIMESTAMPTZ,
  reviewer TEXT,
  review_note TEXT,
  business_id UUID REFERENCES businesses(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE UNIQUE INDEX business_claims_open ON business_claims (e164) WHERE status IN ('verifying', 'verified', 'submitted');
CREATE INDEX ON business_claims (status, submitted_at);
CREATE INDEX ON business_claims (user_id);

-- Documents of a claim, stored in the object store under claims/{claim id}/; deleted with the claim's documents
-- 90 days after the decision (RetentionJob).
CREATE TABLE business_claim_documents (
  claim_id UUID REFERENCES business_claims(id) ON DELETE CASCADE,
  object_key TEXT NOT NULL,
  content_type TEXT NOT NULL,
  size_bytes BIGINT NOT NULL,
  uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (claim_id, object_key));

-- Every action taken in the admin web (who, from where, what, on what).
CREATE TABLE admin_audit (
  id BIGSERIAL PRIMARY KEY,
  at TIMESTAMPTZ NOT NULL DEFAULT now(),
  admin TEXT NOT NULL,
  ip TEXT NOT NULL,
  action TEXT NOT NULL,
  target TEXT NOT NULL,
  detail TEXT);
CREATE INDEX ON admin_audit (at);

-- OTP sources, curated in the admin web (seeded from backend/server/seed on first start). A removed row keeps a
-- deleted_at so apps syncing with ?since= remove it too; the same for sender IDs.
CREATE TABLE otp_app_hashes (
  hash TEXT PRIMARY KEY,
  package_name TEXT NOT NULL,
  app_name TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at TIMESTAMPTZ);
CREATE INDEX ON otp_app_hashes (updated_at);

CREATE TABLE verification_ranges (
  prefix TEXT PRIMARY KEY,
  provider TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at TIMESTAMPTZ);
CREATE INDEX ON verification_ranges (updated_at);

ALTER TABLE sender_ids ADD COLUMN deleted_at TIMESTAMPTZ;
CREATE INDEX ON sender_ids (country_iso, updated_at);

-- Sender-ID import runs (importers framework): one row per importer per run, shown in the admin web.
CREATE TABLE import_runs (
  id BIGSERIAL PRIMARY KEY,
  source TEXT NOT NULL,
  country_iso CHAR(2),
  started_at TIMESTAMPTZ NOT NULL,
  finished_at TIMESTAMPTZ NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('ok', 'failed', 'unavailable')),
  upserted INTEGER NOT NULL DEFAULT 0,
  removed INTEGER NOT NULL DEFAULT 0,
  detail TEXT);
CREATE INDEX ON import_runs (started_at);
