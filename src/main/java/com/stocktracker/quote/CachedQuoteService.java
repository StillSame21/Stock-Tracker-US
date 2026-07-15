package com.stocktracker.quote;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.stocktracker.gateway.MarketClockService;
import com.stocktracker.gateway.MarketDataProvider;

/**
 * Read-side cache in front of {@link MarketDataProvider} for UI/watchlist
 * reads only. TTL is 5s while the market is open, 60s while closed.
 *
 * <p><b>L3.1 — do not reuse this for the alert engine.</b> Cache staleness
 * that's invisible in a UI is a missed alert in the alert engine; Step 5
 * reads the price stream directly and never goes through this class.
 */
@Service
public class CachedQuoteService {

    private static final Duration OPEN_TTL = Duration.ofSeconds(5);
    private static final Duration CLOSED_TTL = Duration.ofSeconds(60);

    private final MarketDataProvider provider;
    private final MarketClockService marketClock;
    private final Cache<String, Quote> cache;

    public CachedQuoteService(MarketDataProvider provider, MarketClockService marketClock) {
        this.provider = provider;
        this.marketClock = marketClock;
        this.cache = Caffeine.newBuilder()
                .expireAfter(new Expiry<String, Quote>() {
                    @Override
                    public long expireAfterCreate(String key, Quote value, long currentTime) {
                        return currentTtl().toNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, Quote value, long currentTime, long currentDuration) {
                        return currentTtl().toNanos();
                    }

                    @Override
                    public long expireAfterRead(String key, Quote value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }

    private Duration currentTtl() {
        return marketClock.getClock().open() ? OPEN_TTL : CLOSED_TTL;
    }

    public Map<String, Quote> getQuotes(Set<String> symbols) {
        if (symbols.isEmpty()) {
            return Map.of();
        }
        Set<String> missing = symbols.stream()
                .filter(symbol -> cache.getIfPresent(symbol) == null)
                .collect(Collectors.toSet());

        if (!missing.isEmpty()) {
            provider.getQuotes(missing).forEach(cache::put);
        }

        Map<String, Quote> result = new LinkedHashMap<>();
        for (String symbol : symbols) {
            Quote quote = cache.getIfPresent(symbol);
            if (quote != null) {
                result.put(symbol, quote);
            }
        }
        return result;
    }
}
