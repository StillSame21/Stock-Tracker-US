package com.stocktracker.stream;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A trade print from the {@code trades} channel — capped at 30 symbols on
 * the free tier (C4), reserved for a hot-list.
 *
 * <p>{@code cancelled} marks a correction/cancellation ({@code "c"}/{@code "x"}
 * message types, L4.3). v1 does not retract an alert already fired on a
 * since-cancelled print — it's logged so the fact is not silently lost, but
 * treating a fired alert as still-fired is the documented v1 trade-off.
 */
public record TradeEvent(String symbol, Instant timestamp, BigDecimal price, long size, boolean cancelled) {
}
