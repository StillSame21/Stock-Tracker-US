package com.stocktracker.gateway;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.stocktracker.gateway.alpaca.AlpacaClockClient;

/**
 * Caches {@code /v2/clock} for a short window so every quote/cache-TTL
 * decision doesn't trigger its own upstream call. If Alpaca is unreachable,
 * falls back to the last known clock (or, with none cached yet, assumes the
 * market is closed — the conservative choice for cache TTLs and the alert
 * engine's extended-hours gate).
 */
@Service
public class MarketClockService {

    private static final Logger log = LoggerFactory.getLogger(MarketClockService.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final AlpacaClockClient clockClient;
    private volatile MarketClock cached;
    private volatile Instant cachedAt = Instant.EPOCH;

    public MarketClockService(AlpacaClockClient clockClient) {
        this.clockClient = clockClient;
    }

    public synchronized MarketClock getClock() {
        if (cached != null && Duration.between(cachedAt, Instant.now()).compareTo(CACHE_TTL) < 0) {
            return cached;
        }
        try {
            cached = clockClient.fetchClock();
            cachedAt = Instant.now();
        } catch (RuntimeException e) {
            if (cached == null) {
                log.warn("Could not fetch market clock and none cached — assuming market closed", e);
                return new MarketClock(Instant.now(), false, null, null);
            }
            log.warn("Could not refresh market clock — using last known state", e);
        }
        return cached;
    }
}
