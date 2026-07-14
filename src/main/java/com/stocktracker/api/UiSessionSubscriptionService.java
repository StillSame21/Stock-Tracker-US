package com.stocktracker.api;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import com.stocktracker.gateway.SubscriptionManager;

/**
 * Ref-counts {@code /topic/quotes/{symbol}} subscriptions across every open
 * browser tab and feeds the live set into {@link SubscriptionManager} as the
 * "ui-sessions" bar-interest source. Without this, every tab that ever
 * opened a symbol keeps it subscribed upstream forever — the subscription
 * set grows monotonically until the WS chokes (L7.1).
 */
@Component
public class UiSessionSubscriptionService {

    private static final String TOPIC_PREFIX = "/topic/quotes/";

    private final SubscriptionManager subscriptionManager;
    private final Map<String, AtomicInteger> refCounts = new ConcurrentHashMap<>();
    private final Map<String, String> symbolBySubscriptionKey = new ConcurrentHashMap<>();

    public UiSessionSubscriptionService(SubscriptionManager subscriptionManager) {
        this.subscriptionManager = subscriptionManager;
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(TOPIC_PREFIX)) {
            return;
        }
        String symbol = destination.substring(TOPIC_PREFIX.length());
        symbolBySubscriptionKey.put(subscriptionKey(accessor), symbol);
        refCounts.computeIfAbsent(symbol, s -> new AtomicInteger()).incrementAndGet();
        pushToSubscriptionManager();
    }

    @EventListener
    public void onUnsubscribe(SessionUnsubscribeEvent event) {
        release(subscriptionKey(StompHeaderAccessor.wrap(event.getMessage())));
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String sessionId = StompHeaderAccessor.wrap(event.getMessage()).getSessionId();
        String prefix = sessionId + ":";
        symbolBySubscriptionKey.keySet().stream()
                .filter(key -> key.startsWith(prefix))
                .toList()
                .forEach(this::release);
    }

    private void release(String subscriptionKey) {
        String symbol = symbolBySubscriptionKey.remove(subscriptionKey);
        if (symbol == null) {
            return;
        }
        AtomicInteger count = refCounts.get(symbol);
        if (count != null && count.decrementAndGet() <= 0) {
            refCounts.remove(symbol);
        }
        pushToSubscriptionManager();
    }

    private void pushToSubscriptionManager() {
        subscriptionManager.updateBarInterest("ui-sessions", refCounts.keySet());
    }

    private static String subscriptionKey(StompHeaderAccessor accessor) {
        return accessor.getSessionId() + ":" + accessor.getSubscriptionId();
    }
}
