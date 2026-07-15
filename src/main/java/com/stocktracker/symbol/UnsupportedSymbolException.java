package com.stocktracker.symbol;

import java.util.Set;

public class UnsupportedSymbolException extends RuntimeException {
    public UnsupportedSymbolException(Set<String> unsupported) {
        super("Not a supported US equity symbol: " + String.join(", ", unsupported));
    }
}
