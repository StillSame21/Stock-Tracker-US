package com.stocktracker.symbol;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

/**
 * Enforces "US market only" (requirement #3) at the boundary. Every symbol
 * entering the system — watchlist add, alert create, quote query — passes
 * through here. Backed by an in-memory set refreshed from the {@code symbols}
 * table at boot and after every nightly sync; validation itself never hits
 * the network or the database.
 */
@Component
public class SymbolValidator {

    private final SymbolRepository repository;
    private final AtomicReference<Set<String>> tradableSymbols = new AtomicReference<>(Set.of());

    public SymbolValidator(SymbolRepository repository) {
        this.repository = repository;
    }

    public void reload() {
        tradableSymbols.set(Set.copyOf(repository.findAllTradableSymbols()));
    }

    public boolean isSupported(String symbol) {
        return tradableSymbols.get().contains(symbol.toUpperCase());
    }

    public void validateAll(Set<String> symbols) {
        Set<String> unsupported = new HashSet<>();
        for (String symbol : symbols) {
            if (!isSupported(symbol)) {
                unsupported.add(symbol);
            }
        }
        if (!unsupported.isEmpty()) {
            throw new UnsupportedSymbolException(unsupported);
        }
    }

    /** Number of symbols currently loaded — for health/metrics. */
    public int size() {
        return tradableSymbols.get().size();
    }
}
