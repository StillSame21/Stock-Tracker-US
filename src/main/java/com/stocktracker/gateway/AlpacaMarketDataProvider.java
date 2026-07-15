package com.stocktracker.gateway;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.stocktracker.gateway.alpaca.AlpacaSnapshotClient;
import com.stocktracker.quote.Quote;

/**
 * The only Alpaca-aware implementation of {@link MarketDataProvider}. No
 * Alpaca DTO ever crosses into this class — {@link AlpacaSnapshotClient}
 * already hands back {@link Quote}.
 */
@Component
public class AlpacaMarketDataProvider implements MarketDataProvider {

    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);

    private final AlpacaSnapshotClient snapshotClient;
    private final String feed;
    private final Map<String, Quote> lastGood = new ConcurrentHashMap<>();

    public AlpacaMarketDataProvider(AlpacaSnapshotClient snapshotClient, AlpacaProperties properties) {
        this.snapshotClient = snapshotClient;
        this.feed = properties.feed();
    }

    @Override
    public Quote getQuote(String symbol) {
        Quote quote = getQuotes(Set.of(symbol)).get(symbol);
        if (quote == null) {
            throw new QuoteUnavailableException(symbol, null);
        }
        return quote;
    }

    @Override
    public Map<String, Quote> getQuotes(Set<String> symbols) {
        if (symbols.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, Quote> quotes = snapshotClient.fetchQuotes(symbols, STALE_THRESHOLD);
            lastGood.putAll(quotes);
            return quotes;
        } catch (RuntimeException e) {
            // Covers CallNotPermittedException (circuit open) and any other upstream failure.
            return fallbackTo(symbols, e);
        }
    }

    private Map<String, Quote> fallbackTo(Set<String> symbols, RuntimeException cause) {
        Map<String, Quote> degraded = new LinkedHashMap<>();
        for (String symbol : symbols) {
            Quote cached = lastGood.get(symbol);
            if (cached != null) {
                degraded.put(symbol, staleCopy(cached));
            }
        }
        if (degraded.isEmpty()) {
            throw new QuoteUnavailableException(String.join(",", symbols), cause);
        }
        return degraded;
    }

    private static Quote staleCopy(Quote quote) {
        return new Quote(quote.symbol(), quote.lastPrice(), quote.dailyChange(), quote.dailyChangePct(),
                quote.volume(), quote.asOf(), quote.feed(), true);
    }

    @Override
    public String feedName() {
        return feed;
    }
}
