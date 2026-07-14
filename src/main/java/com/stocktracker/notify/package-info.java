/**
 * Notification delivery (Step 6): outbox poller with
 * {@code SELECT ... FOR UPDATE SKIP LOCKED}, {@code NotificationChannel}
 * abstraction (email/webhook/push), bounded retries, per-user rate limit.
 */
package com.stocktracker.notify;
