package com.stocktracker.alert;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.stocktracker.gateway.MarketClockService;
import com.stocktracker.gateway.MarketDataProvider;
import com.stocktracker.quote.Quote;
import com.stocktracker.stream.BarEvent;
import com.stocktracker.stream.SymbolPriceBus;

/**
 * L5.2: on the IEX feed an illiquid symbol can go a whole minute — or several
 * — without printing a bar at all. Absence of a bar is not absence of price
 * movement, so a symbol with active alerts and no recent bar is polled via
 * REST and fed into the bus as a synthetic {@link BarEvent}, so it runs
 * through the exact same {@link AlertEvaluator} path a real bar would.
 *
 * <p>Budgeted against C7 (200 rpm): only symbols that have gone stale are
 * polled, in one batched {@link MarketDataProvider#getQuotes} call, capped at
 * the same size as the trade hot-list.
 *
 * <p>Not needed under the {@code replay} profile — the fixture already
 * contains whatever gaps it contains; polling live REST during a replay
 * would be both pointless and impossible (no network).
 */
@Component
@Profile("!replay")
public class ThinSymbolPoller {

    private static final Logger log = LoggerFactory.getLogger(ThinSymbolPoller.class);

    private static final Duration STALE_THRESHOLD = Duration.ofSeconds(90);
    private static final int MAX_SYMBOLS_PER_POLL = 30; // matches the trade hot-list cap, C4

    private final AlertIndex alertIndex;
    private final MarketDataProvider marketDataProvider;
    private final MarketClockService marketClock;
    private final SymbolPriceBus priceBus;
    private final Clock clock;

    private final Map<String, Instant> lastBarSeenAt = new ConcurrentHashMap<>();

    public ThinSymbolPoller(AlertIndex alertIndex, MarketDataProvider marketDataProvider,
                             MarketClockService marketClock, SymbolPriceBus priceBus, Clock clock) {
        this.alertIndex = alertIndex;
        this.marketDataProvider = marketDataProvider;
        this.marketClock = marketClock;
        this.priceBus = priceBus;
        this.clock = clock;
    }

    @EventListener
    public void onBar(BarEvent event) {
        lastBarSeenAt.put(event.symbol(), clock.instant());
    }

    @Scheduled(fixedDelay = 60, initialDelay = 60, timeUnit = TimeUnit.SECONDS)
    public void poll() {
        if (!marketClock.getClock().open()) {
            return;
        }
        List<String> stale = staleSymbols();
        if (stale.isEmpty()) {
            return;
        }

        Map<String, Quote> quotes = marketDataProvider.getQuotes(Set.copyOf(stale));
        for (String symbol : stale) {
            Quote quote = quotes.get(symbol);
            if (quote == null || quote.lastPrice() == null) {
                continue;
            }
            BarEvent synthetic = new BarEvent(symbol, quote.asOf(), quote.lastPrice(), quote.lastPrice(),
                    quote.lastPrice(), quote.lastPrice(), quote.volume(), false);
            log.debug("Thin-symbol poll: no bar for {} in over {}s, synthesizing one from REST (asOf={})",
                    symbol, STALE_THRESHOLD.getSeconds(), quote.asOf());
            priceBus.publish(synthetic);
        }
    }

    private List<String> staleSymbols() {
        Instant now = clock.instant();
        List<String> stale = new ArrayList<>();
        for (String symbol : alertIndex.trackedSymbols()) {
            Instant lastSeen = lastBarSeenAt.get(symbol);
            if (lastSeen == null || Duration.between(lastSeen, now).compareTo(STALE_THRESHOLD) > 0) {
                stale.add(symbol);
            }
            if (stale.size() == MAX_SYMBOLS_PER_POLL) {
                break;
            }
        }
        return stale;
    }
}
