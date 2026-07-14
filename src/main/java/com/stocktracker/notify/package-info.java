/**
 * Notification delivery (Step 6): outbox poller with
 * {@code SELECT ... FOR UPDATE SKIP LOCKED}, {@code NotificationChannel}
 * abstraction (email/webhook/push), bounded retries, per-user rate limit.
 *
 * <p>The write side ({@link com.stocktracker.notify.NotificationOutbox})
 * exists from Step 5 on — the alert engine enqueues a {@code PENDING} row
 * in the same transaction that flips an alert to {@code TRIGGERED}. It
 * never sends anything itself.
 */
package com.stocktracker.notify;
