package com.stocktracker.alert;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * FK target is the symbol's {@code asset_id}, not its ticker (L2.2 — tickers
 * get reused after a delisting). {@code armed} is the ARMED/TRIGGERED
 * hysteresis flag and is persisted, not held in memory, so it survives a
 * restart without a duplicate fire (Step 5 acceptance criteria).
 * {@code status} is the separate lifecycle flag: an alert on a delisted
 * symbol gets disabled here, never deleted (L2.3).
 */
@Entity
@Table(name = "alerts")
public class Alert {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Condition condition;

    @Column(nullable = false)
    private BigDecimal threshold;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "cooldown_seconds", nullable = false)
    private int cooldownSeconds = 3600;

    @Column(name = "last_fired_at")
    private Instant lastFiredAt;

    @Column(nullable = false)
    private boolean armed = true;

    @Column(nullable = false)
    private boolean extendedHours = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Alert() {
        // JPA
    }

    public Alert(UUID userId, UUID assetId, Condition condition, BigDecimal threshold,
                 int cooldownSeconds, boolean extendedHours) {
        this.userId = userId;
        this.assetId = assetId;
        this.condition = condition;
        this.threshold = threshold;
        this.cooldownSeconds = cooldownSeconds;
        this.extendedHours = extendedHours;
    }

    public UUID getId() {
        return id;
    }

    /**
     * Package-private for test fixtures only. {@code @GeneratedValue} means a
     * directly-constructed (not persisted) entity has a null id, which
     * {@code AlertEvaluator}'s idempotency map — a plain {@code ConcurrentHashMap},
     * which rejects null keys — cannot tolerate. Production code never needs this:
     * every {@code Alert} the evaluator sees was already loaded from the database
     * via {@code AlertIndex}, so it always has a real id by then.
     */
    void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public Condition getCondition() {
        return condition;
    }

    public BigDecimal getThreshold() {
        return threshold;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public Instant getLastFiredAt() {
        return lastFiredAt;
    }

    public void setLastFiredAt(Instant lastFiredAt) {
        this.lastFiredAt = lastFiredAt;
    }

    public boolean isArmed() {
        return armed;
    }

    public void setArmed(boolean armed) {
        this.armed = armed;
    }

    public boolean isExtendedHours() {
        return extendedHours;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
