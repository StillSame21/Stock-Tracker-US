package com.stocktracker.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.stocktracker.gateway.AlpacaProperties;
import com.stocktracker.stream.BarEvent;

/**
 * Fans bar events out to {@code /topic/quotes/{symbol}}, throttled to at
 * most 1 update per symbol per second — no browser needs 50 updates a
 * second, and it's needless load on both server and client (Step 7 task 2).
 */
@Component
public class QuoteBroadcaster {

    private static final Duration THROTTLE = Duration.ofSeconds(1);
    private static final Duration STALE_THRESHOLD = Duration.ofSeconds(60);

    private final SimpMessagingTemplate messagingTemplate;
    private final String feed;
    private final Clock clock;
    private final Map<String, Instant> lastSentAt = new ConcurrentHashMap<>();

    public QuoteBroadcaster(SimpMessagingTemplate messagingTemplate, AlpacaProperties properties, Clock clock) {
        this.messagingTemplate = messagingTemplate;
        this.feed = properties.feed();
        this.clock = clock;
    }

    @EventListener
    public void onBar(BarEvent event) {
        Instant now = clock.instant();
        Instant last = lastSentAt.get(event.symbol());
        if (last != null && Duration.between(last, now).compareTo(THROTTLE) < 0) {
            return;
        }
        lastSentAt.put(event.symbol(), now);

        boolean stale = event.barTimestamp() == null
                || Duration.between(event.barTimestamp(), now).compareTo(STALE_THRESHOLD) > 0;
        QuoteUpdate update = new QuoteUpdate(event.symbol(), event.close(), event.volume(),
                event.barTimestamp(), stale, feed);
        messagingTemplate.convertAndSend("/topic/quotes/" + event.symbol(), update);
    }
}
