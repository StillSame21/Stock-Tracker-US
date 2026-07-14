package com.stocktracker.notify;

import java.util.UUID;

/**
 * Write side of the outbox pattern. The alert engine calls this in the same
 * transaction that flips an alert to {@code TRIGGERED} — it never sends
 * anything itself. Step 6's poller reads {@code PENDING} rows and does the
 * actual delivery.
 */
public interface NotificationOutbox {
    void enqueue(UUID alertId, String channel, String payloadJson);
}
