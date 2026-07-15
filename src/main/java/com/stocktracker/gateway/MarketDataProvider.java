package com.stocktracker.gateway;

import java.util.Map;
import java.util.Set;

import com.stocktracker.quote.Quote;

/**
 * The only contract the rest of the application sees. Nothing outside the
 * {@code gateway} package may reference an Alpaca type — swap the
 * implementation and nothing else changes.
 */
public interface MarketDataProvider {

    Quote getQuote(String symbol);

    /** Batched lookup — one upstream call per chunk, never one call per symbol. */
    Map<String, Quote> getQuotes(Set<String> symbols);

    /** "iex" | "sip" — surfaced to the UI so C5 (feed disclosure) isn't silent. */
    String feedName();
}
