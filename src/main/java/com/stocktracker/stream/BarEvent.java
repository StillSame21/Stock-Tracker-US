package com.stocktracker.stream;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A minute bar from the {@code bars} channel — unlimited symbols, ~60s
 * latency (C4). This is the primary alert input (L4.1: alerts evaluate on
 * 1-minute bars, never promise real-time on the free tier).
 *
 * <p>{@code (symbol, barTimestamp)} is the natural idempotency key: Alpaca
 * can emit a revised {@code updatedBars} message for a bar already seen
 * (L4.4), so consumers must key off this pair, not arrival order.
 */
public record BarEvent(
        String symbol,
        Instant barTimestamp,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume,
        boolean updated
) {
}
