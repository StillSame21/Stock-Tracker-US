package com.stocktracker.api;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.stocktracker.gateway.AlpacaProperties;
import com.stocktracker.stream.BarEvent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class QuoteBroadcasterTest {

    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final AlpacaProperties properties = new AlpacaProperties("k", "s", "u1", "u2", "u3", "iex", 180, "live", null);

    private static BarEvent bar(Instant t, double close) {
        return new BarEvent("AAPL", t, BigDecimal.valueOf(close), BigDecimal.valueOf(close),
                BigDecimal.valueOf(close), BigDecimal.valueOf(close), 1000, false);
    }

    @Test
    void secondUpdateWithinOneSecondIsThrottled() {
        Instant t0 = Instant.parse("2026-07-14T15:30:00Z");
        var clock = new MutableClock(t0);
        QuoteBroadcaster broadcaster = new QuoteBroadcaster(messagingTemplate, properties, clock);

        broadcaster.onBar(bar(t0, 150));
        clock.set(t0.plusMillis(500));
        broadcaster.onBar(bar(t0, 150.5));

        verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/quotes/AAPL"), any(QuoteUpdate.class));
    }

    @Test
    void updateAfterThrottleWindowIsSent() {
        Instant t0 = Instant.parse("2026-07-14T15:30:00Z");
        var clock = new MutableClock(t0);
        QuoteBroadcaster broadcaster = new QuoteBroadcaster(messagingTemplate, properties, clock);

        broadcaster.onBar(bar(t0, 150));
        clock.set(t0.plusSeconds(2));
        broadcaster.onBar(bar(t0, 150.5));

        verify(messagingTemplate, times(2)).convertAndSend(eq("/topic/quotes/AAPL"), any(QuoteUpdate.class));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
