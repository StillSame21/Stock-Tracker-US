package com.stocktracker.gateway.alpaca;

/**
 * One symbol's entry from {@code GET /v2/stocks/snapshots}. All fields are
 * nullable: outside market hours {@code latestTrade}/{@code dailyBar} may be
 * absent and only {@code prevDailyBar} is populated (L1.3).
 */
record AlpacaSnapshot(
        AlpacaTrade latestTrade,
        AlpacaBar dailyBar,
        AlpacaBar prevDailyBar
) {
}
