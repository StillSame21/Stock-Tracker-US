package com.stocktracker.api;

import java.math.BigDecimal;
import java.time.Instant;

/** Lightweight push payload — not the full {@code Quote} record, just enough for a live UI row. */
public record QuoteUpdate(String symbol, BigDecimal price, long volume, Instant asOf, boolean stale, String feed) {
}
