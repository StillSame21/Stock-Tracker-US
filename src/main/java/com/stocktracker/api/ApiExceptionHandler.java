package com.stocktracker.api;

import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.stocktracker.gateway.QuoteUnavailableException;
import com.stocktracker.symbol.UnsupportedSymbolException;

/**
 * Translates domain failures into controlled HTTP responses. An upstream
 * outage must surface as a 503 with a clear body, never a bare 500
 * (Step 1 acceptance criteria).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(QuoteUnavailableException.class)
    public ResponseEntity<Map<String, Object>> onQuoteUnavailable(QuoteUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorBody(e.getMessage()));
    }

    @ExceptionHandler(UnsupportedSymbolException.class)
    public ResponseEntity<Map<String, Object>> onUnsupportedSymbol(UnsupportedSymbolException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> onNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(e.getMessage()));
    }

    private static Map<String, Object> errorBody(String message) {
        return Map.of("error", message, "timestamp", Instant.now().toString());
    }
}
