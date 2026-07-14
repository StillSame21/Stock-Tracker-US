package com.stocktracker.quote;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Vendor-neutral price snapshot. {@code asOf} is the vendor's own timestamp,
 * never {@code Instant.now()} — with an IEX-only feed a thin symbol may not
 * have printed for an hour, and {@code stale} is the only signal that
 * distinguishes "market closed" from "vendor is broken".
 */
public record Quote(
        String symbol,
        BigDecimal lastPrice,
        BigDecimal dailyChange,
        BigDecimal dailyChangePct,
        long volume,
        Instant asOf,
        String feed,
        boolean stale
) {
}
