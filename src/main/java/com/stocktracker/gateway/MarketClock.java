package com.stocktracker.gateway;

import java.time.Instant;

/**
 * Vendor-neutral market state from {@code GET /v2/clock}. Consumers use this
 * instead of hardcoding 9:30–16:00 ET, holidays, or DST math (SETUP.md §4.3).
 */
public record MarketClock(Instant timestamp, boolean open, Instant nextOpen, Instant nextClose) {
}
