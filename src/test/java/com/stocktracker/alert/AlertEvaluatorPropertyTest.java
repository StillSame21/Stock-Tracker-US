package com.stocktracker.alert;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.stocktracker.gateway.MarketClock;
import com.stocktracker.gateway.MarketClockService;
import com.stocktracker.gateway.MarketDataProvider;
import com.stocktracker.notify.NotificationOutbox;
import com.stocktracker.quote.Quote;
import com.stocktracker.stream.BarEvent;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Step 5 acceptance criterion: for any price walk, the number of fires never
 * exceeds {@code duration / cooldown}. This is a property of the cooldown
 * gate alone (re-arming requires cooldown to have elapsed), so it should
 * hold no matter how the price walk is shaped — including adversarial
 * oscillation exactly around the threshold.
 */
class AlertEvaluatorPropertyTest {

    private static final int COOLDOWN_SECONDS = 300;
    private static final long BAR_INTERVAL_SECONDS = 60;

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advanceTo(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @Property(tries = 200)
    void firesNeverExceedDurationOverCooldown(@ForAll("priceWalks") List<Double> walk) {
        Instant start = Instant.parse("2026-07-14T15:30:00Z");
        MutableClock clock = new MutableClock(start);

        AlertRepository alertRepository = mock(AlertRepository.class);
        when(alertRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AlertIndex alertIndex = mock(AlertIndex.class);
        MarketClockService marketClockService = mock(MarketClockService.class);
        when(marketClockService.getClock()).thenReturn(new MarketClock(start, true, null, null));
        MarketDataProvider marketDataProvider = mock(MarketDataProvider.class);
        NotificationOutbox notificationOutbox = mock(NotificationOutbox.class);
        AtomicInteger fireCount = new AtomicInteger();
        doAnswer(inv -> {
            fireCount.incrementAndGet();
            return null;
        }).when(notificationOutbox).enqueue(any(), any(), any(), any());

        AlertEvaluator evaluator = new AlertEvaluator(alertRepository, alertIndex, notificationOutbox,
                marketClockService, marketDataProvider, clock, new SimpleMeterRegistry());

        Alert alert = new Alert(UUID.randomUUID(), UUID.randomUUID(), Condition.PRICE_ABOVE,
                BigDecimal.valueOf(150), COOLDOWN_SECONDS, false);
        alert.setId(UUID.randomUUID()); // @GeneratedValue only assigns on persistence

        Instant t = start;
        for (double price : walk) {
            BigDecimal p = BigDecimal.valueOf(price);
            BarEvent bar = new BarEvent("AAPL", t, p, p.add(BigDecimal.valueOf(0.05)),
                    p.subtract(BigDecimal.valueOf(0.05)), p, 1000, false);
            clock.advanceTo(t);
            evaluator.evaluateOne(alert, bar);
            t = t.plusSeconds(BAR_INTERVAL_SECONDS);
        }

        long durationSeconds = BAR_INTERVAL_SECONDS * walk.size();
        long maxAllowedFires = durationSeconds / COOLDOWN_SECONDS + 1;
        assertThat(fireCount.get()).isLessThanOrEqualTo((int) maxAllowedFires);
    }

    @Provide
    Arbitrary<List<Double>> priceWalks() {
        // Deliberately adversarial: dense oscillation right around the 150 threshold.
        return Arbitraries.doubles().between(149.0, 151.0).list().ofMinSize(20).ofMaxSize(150);
    }

    // F1: same cooldown-cap invariant, but driving PCT_CHANGE_UP — the condition that was
    // comparing a raw price against a percentage and firing on nearly every bar. This walk
    // oscillates the *percent change from a fixed prevClose* right around the threshold,
    // which would trip that bug on effectively every tick if it were still present.
    @Property(tries = 200)
    void pctChangeFiresNeverExceedDurationOverCooldown(@ForAll("percentWalks") List<Double> pctWalk) {
        Instant start = Instant.parse("2026-07-14T15:30:00Z");
        MutableClock clock = new MutableClock(start);
        BigDecimal prevClose = BigDecimal.valueOf(100);

        AlertRepository alertRepository = mock(AlertRepository.class);
        when(alertRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AlertIndex alertIndex = mock(AlertIndex.class);
        MarketClockService marketClockService = mock(MarketClockService.class);
        when(marketClockService.getClock()).thenReturn(new MarketClock(start, true, null, null));
        MarketDataProvider marketDataProvider = mock(MarketDataProvider.class);
        when(marketDataProvider.getQuote(any())).thenReturn(new Quote("AAPL", prevClose, BigDecimal.ZERO,
                BigDecimal.ZERO, 0, start, "iex", false));
        NotificationOutbox notificationOutbox = mock(NotificationOutbox.class);
        AtomicInteger fireCount = new AtomicInteger();
        doAnswer(inv -> {
            fireCount.incrementAndGet();
            return null;
        }).when(notificationOutbox).enqueue(any(), any(), any(), any());

        AlertEvaluator evaluator = new AlertEvaluator(alertRepository, alertIndex, notificationOutbox,
                marketClockService, marketDataProvider, clock, new SimpleMeterRegistry());

        Alert alert = new Alert(UUID.randomUUID(), UUID.randomUUID(), Condition.PCT_CHANGE_UP,
                BigDecimal.valueOf(5), COOLDOWN_SECONDS, false); // fire at +5% or more
        alert.setId(UUID.randomUUID());

        Instant t = start;
        for (double pct : pctWalk) {
            BigDecimal close = prevClose.add(prevClose.multiply(BigDecimal.valueOf(pct))
                    .divide(BigDecimal.valueOf(100)));
            BarEvent bar = new BarEvent("AAPL", t, close, close.add(BigDecimal.valueOf(0.05)),
                    close.subtract(BigDecimal.valueOf(0.05)), close, 1000, false);
            clock.advanceTo(t);
            evaluator.evaluateOne(alert, bar);
            t = t.plusSeconds(BAR_INTERVAL_SECONDS);
        }

        long durationSeconds = BAR_INTERVAL_SECONDS * pctWalk.size();
        long maxAllowedFires = durationSeconds / COOLDOWN_SECONDS + 1;
        assertThat(fireCount.get()).isLessThanOrEqualTo((int) maxAllowedFires);
    }

    @Provide
    Arbitrary<List<Double>> percentWalks() {
        // Dense oscillation right around the 5% threshold.
        return Arbitraries.doubles().between(4.0, 6.0).list().ofMinSize(20).ofMaxSize(150);
    }
}
