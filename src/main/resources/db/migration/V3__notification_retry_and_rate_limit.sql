-- Step 6: retry backoff and per-user rate limiting.
-- user_id is denormalized from alerts.user_id at enqueue time so the outbox poller's
-- rate-limit check never needs to join across the notify/alert package boundary.
ALTER TABLE notifications ADD COLUMN user_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000';
ALTER TABLE notifications ALTER COLUMN user_id DROP DEFAULT;
ALTER TABLE notifications ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX idx_notifications_rate_limit ON notifications (user_id, status, sent_at);
