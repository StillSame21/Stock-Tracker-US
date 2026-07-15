package com.stocktracker.alert;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.stocktracker.gateway.MarketClockService;
import com.stocktracker.gateway.MarketDataProvider;
import com.stocktracker.notify.NotificationOutbox;
import com.stocktracker.quote.Quote;
import com.stocktracker.stream.BarEvent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Subscribes to {@link BarEvent} directly off the price stream — never
 * through {@code CachedQuoteService} (L3.1: cache staleness invisible in a
 * UI is a missed alert here).
 *
 * <p>The three things everyone gets wrong, addressed here:
 * <ol>
 *   <li><b>Re-arm hysteresis</b> ({@code armed}, persisted): a naive
 *       {@code if (price > threshold) fire()} sends hundreds of notifications
 *       while a stock oscillates around the threshold. {@code ARMED} only
 *       fires once per crossing; it only returns to {@code ARMED} after price
 *       moves back through a 0.5% re-arm band <em>and</em> the cooldown has
 *       elapsed.</li>
 *   <li><b>Cooldown</b>: enforced independently of hysteresis as a hard cap.</li>
 *   <li><b>Market-hours gating</b>: a pre/after-hours print on a 2%-of-volume
 *       feed is not fired on unless the alert opted into extended hours.</li>
 * </ol>
 *
 * <p>Idempotency (L4.4): per alert, the last <em>original</em> (non-revised)
 * bar timestamp evaluated is tracked. An exact redelivery of that same
 * original bar is skipped. An {@code updatedBars} revision for the same
 * timestamp is always (re-)evaluated — the whole point of L4.4 is that
 * revisions can change the fire decision, e.g. a late trade pushing a bar's
 * high through the threshold. The {@code armed} flag itself already
 * guarantees a revision can't cause a double fire: once {@code TRIGGERED},
 * re-evaluation only checks the re-arm band, never the fire condition again.
 */
@Component
public class AlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluator.class);

    // How far price must move back through the threshold before re-arming (Step 5 design).
    private static final BigDecimal REARM_BAND_PCT = BigDecimal.valueOf(0.5);

    private final AlertRepository alertRepository;
    private final AlertIndex alertIndex;
    private final NotificationOutbox notificationOutbox;
    private final MarketClockService marketClock;
    private final MarketDataProvider marketDataProvider;
    private final Clock clock;
    private final Timer evaluationLatency;
    private final Counter alertsFired;
    private final Counter alertsSuppressedByCooldown;

    private final Map<UUID, Instant> lastEvaluatedOriginalBar = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> previousCloseBySymbol = new ConcurrentHashMap<>();

    public AlertEvaluator(AlertRepository alertRepository, AlertIndex alertIndex,
                           NotificationOutbox notificationOutbox, MarketClockService marketClock,
                           MarketDataProvider marketDataProvider, Clock clock, MeterRegistry meterRegistry) {
        this.alertRepository = alertRepository;
        this.alertIndex = alertIndex;
        this.notificationOutbox = notificationOutbox;
        this.marketClock = marketClock;
        this.marketDataProvider = marketDataProvider;
        this.clock = clock;

        // Step 8 task 1: bar-to-alert latency (p50/p99 come free from the Prometheus
        // registry's histogram), and how often the engine fires vs. suppresses a would-be
        // fire because a re-arm is blocked by cooldown — the two numbers "is it working" and
        // "is cooldown doing its job" boil down to.
        this.evaluationLatency = Timer.builder("alert.evaluation.latency")
                .description("Time from a bar's own timestamp to the evaluator processing it")
                .register(meterRegistry);
        this.alertsFired = Counter.builder("alert.fired")
                .description("Alerts that transitioned ARMED -> TRIGGERED and enqueued a notification")
                .register(meterRegistry);
        this.alertsSuppressedByCooldown = Counter.builder("alert.suppressed")
                .tag("reason", "cooldown")
                .description("Re-arm conditions met but blocked because cooldown hasn't elapsed")
                .register(meterRegistry);
    }

    /** Cleared daily, same schedule as the nightly symbol sync, so PCT_CHANGE baselines don't go stale across sessions. */
    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Kuala_Lumpur")
    public void resetDailyBaselines() {
        previousCloseBySymbol.clear();
    }

    @EventListener
    public void onBar(BarEvent event) {
        List<Alert> alerts = alertIndex.forSymbol(event.symbol());
        if (alerts.isEmpty()) {
            return;
        }
        boolean marketOpen = marketClock.getClock().open();
        for (Alert alert : alerts) {
            if (!alert.isExtendedHours() && !marketOpen) {
                continue;
            }
            if (isDuplicateOfLastEvaluated(alert, event)) {
                continue;
            }
            if (!event.updated()) {
                lastEvaluatedOriginalBar.put(alert.getId(), event.barTimestamp());
            }
            recordEvaluationLatency(event);
            withLoggingContext(alert, event.symbol(), () -> evaluateOne(alert, event));
        }
    }

    private void recordEvaluationLatency(BarEvent event) {
        if (event.barTimestamp() == null) {
            return;
        }
        Duration latency = Duration.between(event.barTimestamp(), clock.instant());
        if (!latency.isNegative()) {
            evaluationLatency.record(latency);
        }
    }

    // Step 8: symbol + alert_id in MDC for the duration of one evaluation, so log lines
    // from anywhere this call chain touches (including the notification outbox write) can
    // be correlated without threading the values through every method signature.
    private static void withLoggingContext(Alert alert, String symbol, Runnable action) {
        MDC.put("symbol", symbol);
        MDC.put("alertId", alert.getId() == null ? "unknown" : alert.getId().toString());
        try {
            action.run();
        } finally {
            MDC.remove("symbol");
            MDC.remove("alertId");
        }
    }

    private boolean isDuplicateOfLastEvaluated(Alert alert, BarEvent event) {
        if (event.updated()) {
            return false; // revisions are always (re-)evaluated
        }
        Instant last = lastEvaluatedOriginalBar.get(alert.getId());
        return event.barTimestamp() != null && event.barTimestamp().equals(last);
    }

    @Transactional
    void evaluateOne(Alert alert, BarEvent bar) {
        if (alert.isArmed()) {
            if (isConditionMet(alert, bar)) {
                fire(alert, bar);
            }
        } else if (isReArmed(alert, bar)) {
            if (cooldownElapsed(alert)) {
                alert.setArmed(true);
                alertRepository.save(alert);
            } else {
                alertsSuppressedByCooldown.increment();
            }
        }
    }

    private void fire(Alert alert, BarEvent bar) {
        alert.setArmed(false);
        alert.setLastFiredAt(clock.instant());
        alertRepository.save(alert);
        notificationOutbox.enqueue(alert.getId(), alert.getUserId(), "EMAIL", buildPayload(alert, bar));
        alertsFired.increment();
        log.info("Alert {} fired on {} ({})", alert.getId(), bar.symbol(), alert.getCondition());
    }

    // L5.1: evaluated against high/low, not close — catches an intra-minute spike, at the
    // cost of alerting on a price the user couldn't necessarily have acted on. The payload
    // labels the bar window so the notification is honest about that trade-off.
    private boolean isConditionMet(Alert alert, BarEvent bar) {
        return switch (alert.getCondition()) {
            case PRICE_ABOVE -> bar.high().compareTo(alert.getThreshold()) >= 0;
            case PRICE_BELOW -> bar.low().compareTo(alert.getThreshold()) <= 0;
            case PCT_CHANGE_UP -> pctChangeMeetsUp(alert, bar);
            case PCT_CHANGE_DOWN -> pctChangeMeetsDown(alert, bar);
            case VOLUME_ABOVE -> BigDecimal.valueOf(bar.volume()).compareTo(alert.getThreshold()) >= 0;
        };
    }

    private boolean isReArmed(Alert alert, BarEvent bar) {
        BigDecimal band = alert.getThreshold().multiply(REARM_BAND_PCT)
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        return switch (alert.getCondition()) {
            case PRICE_ABOVE -> bar.close().compareTo(alert.getThreshold().subtract(band)) < 0;
            case PRICE_BELOW -> bar.close().compareTo(alert.getThreshold().add(band)) > 0;
            case PCT_CHANGE_UP -> {
                BigDecimal pct = percentChange(bar);
                yield pct != null && pct.compareTo(alert.getThreshold().subtract(REARM_BAND_PCT)) < 0;
            }
            case PCT_CHANGE_DOWN -> {
                BigDecimal pct = percentChange(bar);
                yield pct != null && pct.compareTo(alert.getThreshold().negate().add(REARM_BAND_PCT)) > 0;
            }
            // Volume spikes aren't mean-reverting around a threshold the way price is —
            // re-arm as soon as the cooldown allows rather than defining a band. Documented
            // simplification.
            case VOLUME_ABOVE -> true;
        };
    }

    private boolean pctChangeMeetsUp(Alert alert, BarEvent bar) {
        BigDecimal pct = percentChange(bar);
        return pct != null && pct.compareTo(alert.getThreshold()) >= 0;
    }

    private boolean pctChangeMeetsDown(Alert alert, BarEvent bar) {
        BigDecimal pct = percentChange(bar);
        return pct != null && pct.compareTo(alert.getThreshold().negate()) <= 0;
    }

    // L5.3: "percent change from what?" — this codebase's answer is previous close, matching
    // Quote.dailyChangePct(). percentChange is (bar.close - prevClose) / prevClose * 100 against
    // the *live* bar price — previousClose() alone is just the cached baseline, never a percent.
    private BigDecimal percentChange(BarEvent bar) {
        BigDecimal prevClose = previousClose(bar.symbol());
        if (prevClose == null || prevClose.signum() == 0) {
            return null;
        }
        return bar.close().subtract(prevClose)
                .divide(prevClose, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    // Cached per symbol for the process's current trading day; resetDailyBaselines() clears
    // it (wired to the nightly symbol sync).
    private BigDecimal previousClose(String symbol) {
        return previousCloseBySymbol.computeIfAbsent(symbol, this::lookupPreviousClose);
    }

    private BigDecimal lookupPreviousClose(String symbol) {
        try {
            Quote quote = marketDataProvider.getQuote(symbol);
            if (quote.dailyChange() == null || quote.lastPrice() == null) {
                return null;
            }
            return quote.lastPrice().subtract(quote.dailyChange());
        } catch (RuntimeException e) {
            log.warn("Could not resolve previous close for {} — PCT_CHANGE alerts on it won't fire until this succeeds", symbol, e);
            return null;
        }
    }

    private boolean cooldownElapsed(Alert alert) {
        if (alert.getLastFiredAt() == null) {
            return true;
        }
        return Duration.between(alert.getLastFiredAt(), clock.instant()).getSeconds() >= alert.getCooldownSeconds();
    }

    private static String buildPayload(Alert alert, BarEvent bar) {
        return """
                {"symbol":"%s","condition":"%s","threshold":%s,"barClose":%s,"barHigh":%s,"barLow":%s,"barTimestamp":"%s"}"""
                .formatted(bar.symbol(), alert.getCondition(), alert.getThreshold(),
                        bar.close(), bar.high(), bar.low(), bar.barTimestamp());
    }
}
