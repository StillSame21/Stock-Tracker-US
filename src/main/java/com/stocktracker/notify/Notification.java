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

    @Column(name = "sent_at")
    private Instant sentAt;

    protected Notification() {
        // JPA
    }

    public Notification(UUID alertId, String channel, String payload) {
        this.alertId = alertId;
        this.channel = channel;
        this.payload = payload;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAlertId() {
        return alertId;
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

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }
}
