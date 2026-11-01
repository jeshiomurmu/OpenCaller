-- Phase 8, profiles and social (docs/architecture.md §7, "Later migrations"). V1 stays identical to §7.

-- Contact requests (docs/prd.md F-SO-05): one row per requester and target; when the owner answered.
ALTER TABLE contact_requests ADD COLUMN decided_at TIMESTAMPTZ;
CREATE UNIQUE INDEX contact_requests_pair ON contact_requests (requester_id, target_id);
CREATE INDEX ON contact_requests (target_id, status);

-- Profile views: lists by kind for the viewed user, the viewer side for account deletion, and the 90-day retention.
CREATE INDEX ON profile_views (viewed_user_id, kind, at);
CREATE INDEX ON profile_views (viewer_user_id);
CREATE INDEX ON profile_views (at);

-- Deleted accounts waiting for the purge job (docs/prd.md F-SO-07).
CREATE INDEX users_deleted ON users (deleted_at) WHERE deleted_at IS NOT NULL;
