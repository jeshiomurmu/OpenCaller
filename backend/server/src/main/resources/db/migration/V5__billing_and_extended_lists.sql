-- Phase 10, supporter tier (docs/prd.md F-UT-08) and the extended spam list (F-PR-03). V1 stays identical to
-- docs/architecture.md §7.

-- Google Play subscriptions verified by POST /v1/billing/verify. One row per user and platform (the latest
-- purchase: an upgrade or downgrade replaces the row, Google links the new token to the old one). The token itself
-- is kept to check the subscription again before it expires; purchase_token_hash stays unique so one purchase never
-- unlocks two accounts. status is Google's subscriptionState; entitled_until is when access ends unless a later
-- check extends it.
ALTER TABLE subscriptions ADD COLUMN purchase_token TEXT;
ALTER TABLE subscriptions ADD COLUMN base_plan_id TEXT;
ALTER TABLE subscriptions ADD COLUMN tier TEXT NOT NULL DEFAULT 'none'
  CHECK (tier IN ('none', 'supporter', 'gold', 'family'));
ALTER TABLE subscriptions ADD COLUMN auto_renewing BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE subscriptions ADD COLUMN acknowledged BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE subscriptions ADD COLUMN test_purchase BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE subscriptions ADD COLUMN entitled_until TIMESTAMPTZ;
ALTER TABLE subscriptions ADD COLUMN checked_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE UNIQUE INDEX subscriptions_purchase_token_hash ON subscriptions (purchase_token_hash);
CREATE INDEX subscriptions_entitled_until ON subscriptions (entitled_until);

-- users.supporter_tier (V1) also takes 'family'; billing keeps it current, tiers set by hand without a purchase stay.
ALTER TABLE users ADD CONSTRAINT users_supporter_tier CHECK (supporter_tier IN ('none', 'supporter', 'gold', 'family'));
