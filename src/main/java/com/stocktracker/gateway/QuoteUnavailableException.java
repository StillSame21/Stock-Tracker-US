package com.stocktracker.gateway;

/**
 * Thrown when Alpaca is unreachable (circuit open or retries exhausted) and
 * there is no last-known quote to fall back to. Mapped to HTTP 503 —
 * never a bare 500 (Step 1 acceptance criteria).
 */
public class QuoteUnavailableException extends RuntimeException {
    public QuoteUnavailableException(String symbol, Throwable cause) {
        super("No quote available for " + symbol + " and upstream is unavailable", cause);
    }
}
