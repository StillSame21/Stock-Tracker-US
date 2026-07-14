/**
 * Quote domain: the {@code Quote} record, quote caching (Caffeine, Step 3)
 * and watchlist quote assembly. Reads through {@code MarketDataProvider};
 * never through Alpaca types directly.
 */
package com.stocktracker.quote;
