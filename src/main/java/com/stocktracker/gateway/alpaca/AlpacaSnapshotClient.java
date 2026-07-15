package com.stocktracker.gateway.alpaca;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.stocktracker.gateway.AlpacaProperties;
import com.stocktracker.gateway.AlpacaResilience;
import com.stocktracker.quote.Quote;

/**
 * Thin wrapper around {@code GET /v2/stocks/snapshots}. This is the only
 * class that touches the Alpaca REST wire format — it hands back {@link Quote}
 * directly so no {@code Alpaca*} DTO ever needs to leave this package, not
 * even into the parent {@code gateway} package.
 */
@Component
public class AlpacaSnapshotClient {

    // L1.1: the symbols query param has a URL-length ceiling. Chunk well under it.
    private static final int MAX_SYMBOLS_PER_CALL = 100;

    private static final ParameterizedTypeReference<Map<String, AlpacaSnapshot>> SNAPSHOT_MAP =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final AlpacaResilience resilience;
    private final String feed;

    public AlpacaSnapshotClient(RestClient alpacaRestClient, AlpacaResilience resilience, AlpacaProperties properties) {
        this.restClient = alpacaRestClient;
        this.resilience = resilience;
        this.feed = properties.feed();
    }

    public Map<String, Quote> fetchQuotes(Set<String> symbols, Duration staleThreshold) {
        Map<String, Quote> merged = new LinkedHashMap<>();
        for (List<String> chunk : chunk(symbols, MAX_SYMBOLS_PER_CALL)) {
            fetchChunk(chunk).forEach((symbol, snapshot) ->
                    merged.put(symbol, AlpacaQuoteMapper.map(symbol, snapshot, feed, staleThreshold)));
        }
        return merged;
    }

    private Map<String, AlpacaSnapshot> fetchChunk(List<String> symbols) {
        String symbolsParam = String.join(",", symbols);
        Map<String, AlpacaSnapshot> body = resilience.call(() -> restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v2/stocks/snapshots")
                        .queryParam("symbols", symbolsParam)
                        .queryParam("feed", feed)   // C6: never let the default silently pick a feed
                        .build())
                .retrieve()
                .body(SNAPSHOT_MAP));
        return body == null ? Map.of() : body;
    }

    static List<List<String>> chunk(Set<String> symbols, int size) {
        List<List<String>> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>(size);
        for (String symbol : symbols) {
            current.add(symbol);
            if (current.size() == size) {
                chunks.add(current);
                current = new ArrayList<>(size);
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }
}
