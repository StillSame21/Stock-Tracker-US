package com.stocktracker.alert;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

/**
 * Step 8 task 3: every 15 minutes, REST-poll a sample of symbols with active
 * alerts and compare against what the stream last delivered for them. If a
 * symbol has active alerts but no bar has arrived in over 3 minutes during
 * market hours — despite Alpaca's REST API still returning fresh quotes for
 * it — the stream is silently failing for that symbol. Logged loudly; this
 * is deliberately a detection mechanism only; a real deployment should wire
 * this signal (or {@code StreamHealthIndicator}) into actual paging, which
 * needs external credentials this environment doesn't have.
 */
@Component
@Profile("!replay")
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private static final int SAMPLE_SIZE = 5;
    private static final Duration STALE_STREAM_THRESHOLD = Duration.ofMinutes(3);

    private final AlertIndex alertIndex;
    private final MarketDataProvider marketDataProvider;
    private final MarketClockService marketClock;
    private final Map<String, Instant> lastBarSeenAt = new ConcurrentHashMap<>();

    public ReconciliationService(AlertIndex alertIndex, MarketDataProvider marketDataProvider,
                                  MarketClockService marketClock) {
        this.alertIndex = alertIndex;
        this.marketDataProvider = marketDataProvider;
        this.marketClock = marketClock;
    }

    @EventListener
    public void onBar(BarEvent event) {
        lastBarSeenAt.put(event.symbol(), Instant.now());
    }

    @Scheduled(fixedDelay = 15, initialDelay = 15, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    public void reconcile() {
        if (!marketClock.getClock().open()) {
            return; // absence of bars while closed is expected, not a discrepancy
        }
        List<String> sample = sampleSymbols();
        if (sample.isEmpty()) {
            return;
        }

        Map<String, Quote> quotes = marketDataProvider.getQuotes(Set.copyOf(sample));
        for (String symbol : sample) {
            Quote quote = quotes.get(symbol);
            if (quote == null) {
                continue;
            }
            Instant lastBar = lastBarSeenAt.get(symbol);
            boolean stale = lastBar == null
                    || Duration.between(lastBar, Instant.now()).compareTo(STALE_STREAM_THRESHOLD) > 0;
            if (stale) {
                log.warn("RECONCILIATION DISCREPANCY: no stream bar for {} in over {} minutes, but REST "
                                + "still reports lastPrice={} asOf={} — the stream may be silently dead for this symbol",
                        symbol, STALE_STREAM_THRESHOLD.toMinutes(), quote.lastPrice(), quote.asOf());
            }
        }
    }

    private List<String> sampleSymbols() {
        List<String> all = new ArrayList<>(alertIndex.trackedSymbols());
        if (all.size() <= SAMPLE_SIZE) {
            return all;
        }
        java.util.Collections.shuffle(all);
        return all.subList(0, SAMPLE_SIZE);
    }
}
