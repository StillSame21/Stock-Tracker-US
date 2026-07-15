package com.stocktracker.notify;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "alert_id", nullable = false)
    private UUID alertId;

    // Denormalized from alerts.user_id at enqueue time, purely so the outbox poller's
    // per-user rate-limit check never needs a cross-package join into the alert entity.
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String channel;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(nullable = false)
    private String payload;

    @Column(nullable = false)
    private int attempt = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "sent_at")
    private Instant sentAt;

    protected Notification() {
        // JPA
    }

    public Notification(UUID alertId, UUID userId, String channel, String payload) {
        this.alertId = alertId;
        this.userId = userId;
        this.channel = channel;
        this.payload = payload;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAlertId() {
        return alertId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getChannel() {
        return channel;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPayload() {
        return payload;
    }

    public int getAttempt() {
        return attempt;
    }

    public void incrementAttempt() {
        this.attempt++;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }
}
