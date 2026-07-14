package com.stocktracker.alert;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.stocktracker.gateway.MarketClock;
import com.stocktracker.gateway.MarketClockService;
import com.stocktracker.gateway.MarketDataProvider;
import com.stocktracker.notify.NotificationOutbox;
import com.stocktracker.stream.BarEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertEvaluatorTest {

    private final AlertRepository alertRepository = mock(AlertRepository.class);
    private final AlertIndex alertIndex = mock(AlertIndex.class);
    private final NotificationOutbox notificationOutbox = mock(NotificationOutbox.class);
    private final MarketClockService marketClockService = mock(MarketClockService.class);
    private final MarketDataProvider marketDataProvider = mock(MarketDataProvider.class);

    private AlertEvaluator evaluator;

    @BeforeEach
    void setUp() {
        when(alertRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(marketClockService.getClock()).thenReturn(new MarketClock(Instant.now(), true, null, null));
        evaluator = new AlertEvaluator(alertRepository, alertIndex, notificationOutbox, marketClockService,
                marketDataProvider, Clock.fixed(Instant.parse("2026-07-14T15:00:00Z"), ZoneOffset.UTC));
    }

    private static BarEvent bar(String symbol, Instant t, double open, double high, double low, double close, long volume, boolean updated) {
        return new BarEvent(symbol, t, BigDecimal.valueOf(open), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close), volume, updated);
    }

    // AlertEvaluator's idempotency map keys off Alert.getId(), which @GeneratedValue only
    // populates on persistence — a directly-constructed Alert has a null id otherwise.
    private static Alert newAlert(Condition condition, double threshold, int cooldownSeconds, boolean extendedHours) {
        Alert alert = new Alert(UUID.randomUUID(), UUID.randomUUID(), condition,
                BigDecimal.valueOf(threshold), cooldownSeconds, extendedHours);
        alert.setId(UUID.randomUUID());
        return alert;
    }

    @Test
    void armedAlertFiresOnceWhenThresholdCrossedAndBecomesTriggered() {
        Alert alert = newAlert(Condition.PRICE_ABOVE, 150, 3600, false);

        evaluator.evaluateOne(alert, bar("AAPL", Instant.parse("2026-07-14T15:30:00Z"), 149, 151, 148, 150.5, 1000, false));

        assertThat(alert.isArmed()).isFalse();
        assertThat(alert.getLastFiredAt()).isNotNull();
        verify(notificationOutbox, times(1)).enqueue(any(), any(), anyString(), anyString());
    }

    @Test
    void triggeredAlertDoesNotFireAgainWhilePriceStaysAboveReArmBand() {
        Alert alert = newAlert(Condition.PRICE_ABOVE, 150, 60, false);

        evaluator.evaluateOne(alert, bar("AAPL", Instant.parse("2026-07-14T15:30:00Z"), 149, 151, 148, 150.5, 1000, false));
        assertThat(alert.isArmed()).isFalse();

        // Oscillates just above/below 150 but never drops below the 0.5% re-arm band (149.25) —
        // must not fire again.
        for (int i = 0; i < 20; i++) {
            evaluator.evaluateOne(alert, bar("AAPL", Instant.parse("2026-07-14T15:3" + (1 + i % 8) + ":00Z"),
                    149.9, 150.6, 149.7, 150.1, 1000, false));
        }

        verify(notificationOutbox, times(1)).enqueue(any(), any(), anyString(), anyString());
    }

    @Test
    void reArmsAfterPriceDropsThroughBandAndCooldownElapsed() throws InterruptedException {
        Alert alert = newAlert(Condition.PRICE_ABOVE, 150, 0, false); // 0s cooldown for a fast test

        evaluator.evaluateOne(alert, bar("AAPL", Instant.parse("2026-07-14T15:30:00Z"), 149, 151, 148, 150.5, 1000, false));
        assertThat(alert.isArmed()).isFalse();

        // Drop below the re-arm band (< 149.25)
        evaluator.evaluateOne(alert, bar("AAPL", Instant.parse("2026-07-14T15:31:00Z"), 149, 149.1, 148, 148.5, 1000, false));
        assertThat(alert.isArmed()).isTrue();

        // Crosses back above threshold — should fire again.
        evaluator.evaluateOne(alert, bar("AAPL", Instant.parse("2026-07-14T15:32:00Z"), 149, 151, 148, 150.5, 1000, false));
        assertThat(alert.isArmed()).isFalse();

        verify(notificationOutbox, times(2)).enqueue(any(), any(), anyString(), anyString());
    }

    @Test
    void extendedHoursNotOptedInSkipsEvaluationWhenMarketClosed() {
        when(marketClockService.getClock()).thenReturn(new MarketClock(Instant.now(), false, null, null));
        // Directly calling onBar (which applies the market-hours gate) rather than evaluateOne.
        Alert alert = newAlert(Condition.PRICE_ABOVE, 150, 3600, false);
        when(alertIndex.forSymbol("AAPL")).thenReturn(java.util.List.of(alert));

        evaluator.onBar(bar("AAPL", Instant.parse("2026-07-14T15:30:00Z"), 149, 151, 148, 150.5, 1000, false));

        assertThat(alert.isArmed()).isTrue(); // never evaluated, so still armed with no fire
        verify(notificationOutbox, times(0)).enqueue(any(), any(), anyString(), anyString());
    }

    @Test
    void extendedHoursOptedInStillEvaluatesWhenMarketClosed() {
        when(marketClockService.getClock()).thenReturn(new MarketClock(Instant.now(), false, null, null));
        Alert alert = newAlert(Condition.PRICE_ABOVE, 150, 3600, true);
        when(alertIndex.forSymbol("AAPL")).thenReturn(java.util.List.of(alert));

        evaluator.onBar(bar("AAPL", Instant.parse("2026-07-14T15:30:00Z"), 149, 151, 148, 150.5, 1000, false));

        assertThat(alert.isArmed()).isFalse();
        verify(notificationOutbox, times(1)).enqueue(any(), any(), anyString(), anyString());
    }

    @Test
    void exactDuplicateOfSameOriginalBarIsNotReEvaluated() {
        Alert alert = newAlert(Condition.PRICE_ABOVE, 150, 0, false);
        when(alertIndex.forSymbol("AAPL")).thenReturn(java.util.List.of(alert));
        Instant t = Instant.parse("2026-07-14T15:30:00Z");

        evaluator.onBar(bar("AAPL", t, 149, 151, 148, 150.5, 1000, false));
        // Re-arm so a second fire would be possible if the dedup didn't work
        evaluator.evaluateOne(alert, bar("AAPL", t, 149, 149.1, 148, 148.5, 1000, false));
        alert.setArmed(true); // simulate re-arm having happened for this test's purposes

        // Same original bar delivered again (e.g. a redelivered WS frame)
        evaluator.onBar(bar("AAPL", t, 149, 151, 148, 150.5, 1000, false));

        verify(notificationOutbox, times(1)).enqueue(any(), any(), anyString(), anyString());
    }

    @Test
    void updatedBarsRevisionIsAlwaysReEvaluatedEvenForSameTimestamp() {
        Alert alert = newAlert(Condition.PRICE_ABOVE, 150, 3600, false);
        when(alertIndex.forSymbol("AAPL")).thenReturn(java.util.List.of(alert));
        Instant t = Instant.parse("2026-07-14T15:30:00Z");

        // Original bar does NOT meet the condition.
        evaluator.onBar(bar("AAPL", t, 149, 149.5, 148, 149, 1000, false));
        assertThat(alert.isArmed()).isTrue();
        verify(notificationOutbox, times(0)).enqueue(any(), any(), anyString(), anyString());

        // A late trade revises the same bar's high above the threshold — must fire.
        evaluator.onBar(bar("AAPL", t, 149, 151, 148, 149, 1000, true));

        assertThat(alert.isArmed()).isFalse();
        verify(notificationOutbox, times(1)).enqueue(any(), any(), anyString(), anyString());
    }
}
