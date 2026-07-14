package com.stocktracker.alert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.stocktracker.gateway.SubscriptionManager;
import com.stocktracker.symbol.Symbol;
import com.stocktracker.symbol.SymbolRepository;

/**
 * In-memory {@code symbol -> active alerts} index, rebuilt on every alert
 * CRUD operation. Bar events arrive keyed by ticker string, but alerts are
 * keyed by {@code asset_id} (L2.2), so this is also where that join happens.
 *
 * <p>Also the bridge back into Step 4: every reload pushes the current
 * alert-covered symbol set into {@link SubscriptionManager} as the "alerts"
 * bar-interest source, and alert counts per symbol as the trade hot-list
 * ranking input — this is the "distinct(symbols with active alerts)" half
 * of Step 4's subscription formula.
 */
@Component
public class AlertIndex {

    private final AlertRepository alertRepository;
    private final SymbolRepository symbolRepository;
    private final SubscriptionManager subscriptionManager;

    private volatile Map<String, List<Alert>> bySymbol = Map.of();

    public AlertIndex(AlertRepository alertRepository, SymbolRepository symbolRepository,
                       SubscriptionManager subscriptionManager) {
        this.alertRepository = alertRepository;
        this.symbolRepository = symbolRepository;
        this.subscriptionManager = subscriptionManager;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void reload() {
        List<Alert> active = alertRepository.findByStatus("ACTIVE");
        Set<UUID> assetIds = active.stream().map(Alert::getAssetId).collect(Collectors.toSet());
        Map<UUID, String> tickerByAssetId = new HashMap<>();
        if (!assetIds.isEmpty()) {
            for (Symbol symbol : symbolRepository.findAllById(assetIds)) {
                tickerByAssetId.put(symbol.getAssetId(), symbol.getSymbol());
            }
        }

        Map<String, List<Alert>> grouped = new HashMap<>();
        for (Alert alert : active) {
            String ticker = tickerByAssetId.get(alert.getAssetId());
            if (ticker != null) {
                grouped.computeIfAbsent(ticker, k -> new ArrayList<>()).add(alert);
            }
        }
        bySymbol = grouped;

        subscriptionManager.updateBarInterest("alerts", grouped.keySet());
        subscriptionManager.updateAlertCounts(grouped.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size())));
    }

    public List<Alert> forSymbol(String symbol) {
        return bySymbol.getOrDefault(symbol, List.of());
    }

    public Set<String> trackedSymbols() {
        return bySymbol.keySet();
    }
}
