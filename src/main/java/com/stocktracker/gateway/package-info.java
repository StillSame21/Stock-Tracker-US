/**
 * Market-data gateway. The ONLY package that knows Alpaca exists.
 *
 * <p>Contains {@code MarketDataProvider} (the vendor-neutral interface),
 * {@code AlpacaMarketDataProvider} (REST snapshots, Step 1) and
 * {@code AlpacaStreamClient} (WebSocket ingestor, Step 4). No Alpaca DTO
 * may leak past this package — enforced with ArchUnit per Step 1's
 * acceptance criteria.
 */
package com.stocktracker.gateway;
