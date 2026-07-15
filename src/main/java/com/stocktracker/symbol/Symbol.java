package com.stocktracker.symbol;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A US equity symbol from Alpaca's asset universe. Primary-keyed on Alpaca's
 * {@code asset_id} UUID, not the ticker — tickers get reused after a
 * delisting (L2.2).
 */
@Entity
@Table(name = "symbols")
public class Symbol {

    @Id
    @Column(name = "asset_id")
    private UUID assetId;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String exchange;

    @Column(nullable = false)
    private boolean tradable;

    @Column(nullable = false)
    private boolean fractionable;

    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt;

    protected Symbol() {
        // JPA
    }

    public Symbol(UUID assetId, String symbol, String name, String exchange, boolean tradable,
                  boolean fractionable, Instant lastSyncedAt) {
        this.assetId = assetId;
        this.symbol = symbol;
        this.name = name;
        this.exchange = exchange;
        this.tradable = tradable;
        this.fractionable = fractionable;
        this.lastSyncedAt = lastSyncedAt;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getName() {
        return name;
    }

    public String getExchange() {
        return exchange;
    }

    public boolean isTradable() {
        return tradable;
    }

    public boolean isFractionable() {
        return fractionable;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }
}
