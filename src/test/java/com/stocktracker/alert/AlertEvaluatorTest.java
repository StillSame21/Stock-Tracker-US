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
import com.stocktracker.quote.Quote;
import com.stocktracker.stream.BarEvent;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

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
                marketDataProvider, Clock.fixed(Instant.parse("2026-07-14T15:00:00Z"), ZoneOffset.UTC),
                new SimpleMeterRegistry());
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

    // F1: PCT_CHANGE previously compared the raw previous-close *price* against the percentage
    // threshold (never touching the live bar), so it fired on nearly every bar. These pin the
    // actual (bar.close - prevClose) / prevClose * 100 computation.

    private void stubPreviousClose(double prevClose) {
        // lookupPreviousClose derives prevClose as lastPrice - dailyChange; dailyChange=0
        // makes lastPrice itself the previous close, the simplest quote to stub.
        when(marketDataProvider.getQuote(anyString())).thenReturn(new Quote("AAPL",
                BigDecimal.valueOf(prevClose), BigDecimal.ZERO, BigDecimal.ZERO, 0,
                Instant.parse("2026-07-14T04:00:00Z"), "iex", false));
    }

    @Test
    void pctChangeUpFiresOnlyWhenLiveBarCrossesThePercentThreshold() {
        stubPreviousClose(100);
        Alert alert = newAlert(Condition.PCT_CHANGE_UP, 5, 3600, false); // fire at +5% or more

        // +4% — below threshold, must not fire.
        evaluator.evaluateOne(alert, bar("AAPL", Instant.parse("2026-07-14T15:30:00Z"), 103, 104.5, 103, 104, 1000, false));
        assertThat(alert.isArmed()).isTrue();
        verify(notificationOutbox, times(0)).enqueue(any(), any(), anyString(), anyString());

        // +6% — crosses the threshold, must fire exactly once.
        evaluator.evaluateOne(alert, bar("AAPL", Instant.parse("2026-07-14T15:31:00Z"), 105, 106.5, 105, 106, 1000, false));
        assertThat(alert.isArmed()).isFalse();
        verify(notificationOutbox, times(1)).enqueue(any(), any(), anyString(), anyString());
    }

    @Test
    void pctChangeDownFiresOnlyWhenLiveBarCrossesTheNegativePercentThreshold() {
        stubPreviousClose(100);
        Alert alert = newAlert(Condition.PCT_CHANGE_DOWN, 5, 3600, false); // fire at -5% or lower

        // -4% — above (less negative than) the threshold, must not fire.
        evaluator.evaluateOne(alert, bar("AAPL", Instant.parse("2026-07-14T15:30:00Z"), 97, 97, 95.5, 96, 1000, false));
        assertThat(alert.isArmed()).isTrue();
        verify(notificationOutbox, times(0)).enqueue(any(), any(), anyString(), anyString());

        // -6% — crosses the threshold, must fire exactly once.
        evaluator.evaluateOne(alert, bar("AAPL", Instant.parse("2026-07-14T15:31:00Z"), 95, 95, 93.5, 94, 1000, false));
        assertThat(alert.isArmed()).isFalse();
        verify(notificationOutbox, times(1)).enqueue(any(), any(), anyString(), anyString());
    }

    @Test
    void pctChangeUpReArmsAfterRetreatingThroughBandAndFiresAgainOnNextCross() {
        stubPreviousClose(100);
        Alert alert = newAlert(Condition.PCT_CHANGE_UP, 5, 0, false); // 0s cooldown for a fast test

        // +6% — fires.
        evaluator.evaluateOne(alert, bar("AAPL", Instant.parse("2026-07-14T15:30:00Z"), 105, 106.5, 105, 106, 1000, false));
        assertThat(alert.isArmed()).isFalse();

        // Retreats to +0.5% — well under the re-arm line (threshold 5% - 0.5% band = 4.5%).
        evaluator.evaluateOne(alert, bar("AAPL", Instant.parse("2026-07-14T15:31:00Z"), 100.5, 100.6, 100.4, 100.5, 1000, false));
        assertThat(alert.isArmed()).isTrue();

        // Crosses +5% again — fires a second time.
        evaluator.evaluateOne(alert, bar("AAPL", Instant.parse("2026-07-14T15:32:00Z"), 105, 106.5, 105, 106, 1000, false));
        assertThat(alert.isArmed()).isFalse();

        verify(notificationOutbox, times(2)).enqueue(any(), any(), anyString(), anyString());
    }
}
