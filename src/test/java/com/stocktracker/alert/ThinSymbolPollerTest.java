package com.stocktracker.alert;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.stocktracker.gateway.MarketClock;
import com.stocktracker.gateway.MarketClockService;
import com.stocktracker.gateway.MarketDataProvider;
import com.stocktracker.quote.Quote;
import com.stocktracker.stream.BarEvent;
import com.stocktracker.stream.SymbolPriceBus;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ThinSymbolPollerTest {

    private final AlertIndex alertIndex = mock(AlertIndex.class);
    private final MarketDataProvider marketDataProvider = mock(MarketDataProvider.class);
    private final MarketClockService marketClockService = mock(MarketClockService.class);
    private final SymbolPriceBus priceBus = mock(SymbolPriceBus.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-14T15:00:00Z"), ZoneOffset.UTC);

    private ThinSymbolPoller poller;

    private void setUp() {
        poller = new ThinSymbolPoller(alertIndex, marketDataProvider, marketClockService, priceBus, clock);
    }

    @Test
    void symbolWithNoRecentBarIsPolledAndSynthesizesABarEvent() {
        setUp();
        when(marketClockService.getClock()).thenReturn(new MarketClock(Instant.now(), true, null, null));
        when(alertIndex.trackedSymbols()).thenReturn(Set.of("SIRI"));
        Quote quote = new Quote("SIRI", BigDecimal.valueOf(4.5), BigDecimal.ZERO, BigDecimal.ZERO, 500,
                Instant.parse("2026-07-14T14:58:00Z"), "iex", false);
        when(marketDataProvider.getQuotes(Set.of("SIRI"))).thenReturn(Map.of("SIRI", quote));

        poller.poll();

        verify(priceBus).publish(argThatBarFor("SIRI", BigDecimal.valueOf(4.5)));
    }

    @Test
    void symbolWithARecentBarIsNotPolledAgain() {
        setUp();
        when(marketClockService.getClock()).thenReturn(new MarketClock(Instant.now(), true, null, null));
        when(alertIndex.trackedSymbols()).thenReturn(Set.of("AAPL"));

        // A real bar just arrived — well within the 90s staleness threshold.
        poller.onBar(new BarEvent("AAPL", clock.instant(), BigDecimal.valueOf(150), BigDecimal.valueOf(150),
                BigDecimal.valueOf(150), BigDecimal.valueOf(150), 1000, false));

        poller.poll();

        verify(marketDataProvider, never()).getQuotes(any());
        verify(priceBus, never()).publish(any(BarEvent.class));
    }

    @Test
    void doesNothingWhenMarketIsClosed() {
        setUp();
        when(marketClockService.getClock()).thenReturn(new MarketClock(Instant.now(), false, null, null));
        when(alertIndex.trackedSymbols()).thenReturn(Set.of("AAPL"));

        poller.poll();

        verify(marketDataProvider, never()).getQuotes(any());
        verify(priceBus, never()).publish(any(BarEvent.class));
    }

    private static BarEvent argThatBarFor(String symbol, BigDecimal price) {
        return org.mockito.ArgumentMatchers.argThat(bar ->
                bar != null && bar.symbol().equals(symbol) && bar.close().compareTo(price) == 0);
    }
}
