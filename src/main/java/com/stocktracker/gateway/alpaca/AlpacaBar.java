package com.stocktracker.gateway.alpaca;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Raw Alpaca OHLCV bar as returned by the snapshots/bars endpoints.
 * Package-private to the {@code gateway} tree — never exposed outside it.
 * Prices are bound directly to {@code BigDecimal}; Jackson parses the JSON
 * numeric literal straight into it without an intermediate {@code double}
 * (L1.2 — never let vendor prices pass through a binary float).
 */
record AlpacaBar(
        Instant t,
        BigDecimal o,
        BigDecimal h,
        BigDecimal l,
        BigDecimal c,
        long v
) {
}
