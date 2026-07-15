package com.stocktracker.gateway;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * The desired subscription set = distinct(symbols with active alerts) ∪
 * distinct(symbols in open UI sessions), for the {@code bars} channel
 * (unlimited, C4). The {@code trades} channel is capped at 30 symbols, so it
 * carries only the busiest ones by active-alert count, re-ranked whenever
 * that count changes.
 *
 * <p>Never resends the full set on a routine change — only the diff. The one
 * exception is a fresh connection/reconnect, where {@link
 * #desiredBarSymbols()}/{@link #desiredTradeSymbols()} are read directly to
 * rebuild state from scratch, since subscriptions don't survive a reconnect.
 */
@Component
public class SubscriptionManager {

    private static final int MAX_HOT_TRADE_SYMBOLS = 30; // C4

    private final Map<String, Set<String>> barInterestBySource = new ConcurrentHashMap<>();
    private final Map<String, Integer> alertCountBySymbol = new ConcurrentHashMap<>();

    private volatile Set<String> currentBarSymbols = Set.of();
    private volatile Set<String> currentTradeSymbols = Set.of();
    private volatile StreamSubscriber subscriber;

    public void setSubscriber(StreamSubscriber subscriber) {
        this.subscriber = subscriber;
    }

    /** {@code source} is a caller-chosen key (e.g. "alerts", or a UI session id) — last write per source wins. */
    public synchronized void updateBarInterest(String source, Set<String> symbols) {
        if (symbols.isEmpty()) {
            barInterestBySource.remove(source);
        } else {
            barInterestBySource.put(source, Set.copyOf(symbols));
        }
        recompute();
    }

    public synchronized void updateAlertCounts(Map<String, Integer> countsBySymbol) {
        alertCountBySymbol.clear();
        alertCountBySymbol.putAll(countsBySymbol);
        recompute();
    }

    private void recompute() {
        Set<String> desiredBars = barInterestBySource.values().stream()
                .flatMap(Set::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> desiredTrades = alertCountBySymbol.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(MAX_HOT_TRADE_SYMBOLS)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        applyDiff(currentBarSymbols, desiredBars, "bars");
        applyDiff(currentTradeSymbols, desiredTrades, "trades");
        currentBarSymbols = desiredBars;
        currentTradeSymbols = desiredTrades;
    }

    private void applyDiff(Set<String> current, Set<String> desired, String channel) {
        StreamSubscriber s = subscriber;
        if (s == null) {
            return;
        }
        Set<String> toAdd = new LinkedHashSet<>(desired);
        toAdd.removeAll(current);
        Set<String> toRemove = new LinkedHashSet<>(current);
        toRemove.removeAll(desired);
        if (!toAdd.isEmpty()) {
            s.subscribe(channel, toAdd);
        }
        if (!toRemove.isEmpty()) {
            s.unsubscribe(channel, toRemove);
        }
    }

    public Set<String> desiredBarSymbols() {
        return currentBarSymbols;
    }

    public Set<String> desiredTradeSymbols() {
        return currentTradeSymbols;
    }
}
