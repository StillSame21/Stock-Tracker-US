-- Step 6: WebhookChannel target. Nullable — a user with no webhook configured
-- simply can't use that channel (NotificationChannel.send throws, handled by
-- the outbox poller's normal retry-then-DEAD path).
ALTER TABLE users ADD COLUMN webhook_url TEXT;
