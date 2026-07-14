package com.stocktracker.gateway;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;

import com.stocktracker.stream.SymbolPriceBus;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * The single WebSocket connection to Alpaca (C3: exactly one per account).
 * Only runs while this process holds stream leadership ({@link
 * LeaderElection}) — a follower never opens a connection.
 *
 * <p>Auth happens as the very first frame after connect (must complete
 * within Alpaca's 10s window — this sends it synchronously in {@code
 * afterConnectionEstablished}, no queueing delay). Subscriptions do not
 * survive a reconnect, so every successful re-auth triggers a full
 * resubscribe from {@link SubscriptionManager}'s current desired state,
 * never an incremental diff.
 */
@Component
@Profile("!replay")
public class AlpacaStreamClient implements WebSocketHandler, StreamSubscriber {

    private static final Logger log = LoggerFactory.getLogger(AlpacaStreamClient.class);

    // No message for 90s during market hours ⇒ assume dead, reconnect.
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration WATCHDOG_INTERVAL = Duration.ofSeconds(30);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final WebSocketClient webSocketClient;
    private final AlpacaProperties properties;
    private final SubscriptionManager subscriptionManager;
    private final AlpacaFrameParser frameParser;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());

    private volatile WebSocketSession session;
    private volatile boolean authenticated;
    private volatile boolean shouldRun;
    private volatile int reconnectAttempts;
    private volatile Instant lastMessageAt = Instant.now();
    private volatile ScheduledFuture<?> watchdogTask;

    // Step 8: reconnect count and current downtime, both exported to /actuator/prometheus.
    // disconnectedSince tracks downtime only while this instance is actually the leader —
    // a follower is never responsible for the connection, so its "downtime" is meaningless.
    private final Counter reconnectCounter;
    private volatile Instant disconnectedSince;

    public AlpacaStreamClient(WebSocketClient webSocketClient, AlpacaProperties properties,
                               SubscriptionManager subscriptionManager, SymbolPriceBus priceBus,
                               ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.webSocketClient = webSocketClient;
        this.properties = properties;
        this.subscriptionManager = subscriptionManager;
        this.objectMapper = objectMapper;
        this.frameParser = new AlpacaFrameParser(objectMapper, priceBus);
        subscriptionManager.setSubscriber(this);

        this.reconnectCounter = Counter.builder("alpaca.stream.reconnects")
                .description("Number of times the Alpaca stream connection has been re-established")
                .register(meterRegistry);
        Gauge.builder("alpaca.stream.downtime_seconds", this, AlpacaStreamClient::currentDowntimeSeconds)
                .description("Seconds since the stream disconnected while this instance held leadership; 0 if connected or not leader")
                .register(meterRegistry);
    }

    @EventListener
    public void onLeadershipAcquired(LeadershipAcquiredEvent event) {
        shouldRun = true;
        reconnectAttempts = 0;
        connect();
    }

    @EventListener
    public void onLeadershipLost(LeadershipLostEvent event) {
        shouldRun = false;
        disconnectedSince = null; // not our problem once we're not the leader
        closeSession();
    }

    /** For {@link StreamHealthIndicator}. */
    double currentDowntimeSeconds() {
        Instant since = disconnectedSince;
        return since == null ? 0 : Duration.between(since, Instant.now()).toSeconds();
    }

    private synchronized void connect() {
        if (!shouldRun || session != null) {
            return;
        }
        try {
            webSocketClient.execute(this, new WebSocketHttpHeaders(), URI.create(properties.streamUrl()))
                    .whenComplete((s, ex) -> {
                        if (ex != null) {
                            log.warn("Stream connect failed", ex);
                            scheduleReconnect();
                        }
                    });
        } catch (RuntimeException e) {
            log.warn("Stream connect failed", e);
            scheduleReconnect();
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession newSession) {
        this.session = newSession;
        this.authenticated = false;
        this.lastMessageAt = Instant.now();
        this.disconnectedSince = null;
        if (reconnectAttempts > 0) {
            reconnectCounter.increment();
        }
        this.reconnectAttempts = 0;
        log.info("Stream connected, authenticating");
        sendRaw(newSession, Map.of("action", "auth", "key", properties.keyId(), "secret", properties.secretKey()));
        startWatchdog();
    }

    @Override
    public void handleMessage(WebSocketSession webSocketSession, WebSocketMessage<?> message) {
        lastMessageAt = Instant.now();
        if (!(message instanceof TextMessage text)) {
            return;
        }
        String successMessage = frameParser.handle(text.getPayload());
        if ("authenticated".equals(successMessage)) {
            authenticated = true;
            log.info("Stream authenticated — resubscribing to full desired state");
            resubscribeFull();
        }
    }

    private void resubscribeFull() {
        sendSubscribe("bars", subscriptionManager.desiredBarSymbols());
        sendSubscribe("trades", subscriptionManager.desiredTradeSymbols());
    }

    @Override
    public void handleTransportError(WebSocketSession webSocketSession, Throwable exception) {
        log.warn("Stream transport error", exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession webSocketSession, CloseStatus closeStatus) {
        log.warn("Stream connection closed: {}", closeStatus);
        session = null;
        authenticated = false;
        stopWatchdog();
        if (shouldRun) {
            if (disconnectedSince == null) {
                disconnectedSince = Instant.now();
            }
            scheduleReconnect();
        }
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    @Override
    public void subscribe(String channel, Set<String> symbols) {
        if (authenticated) {
            sendSubscribe(channel, symbols);
        }
        // If not yet authenticated, resubscribeFull() will pick up the current desired
        // state (which SubscriptionManager already reflects) once auth completes.
    }

    @Override
    public void unsubscribe(String channel, Set<String> symbols) {
        if (authenticated && !symbols.isEmpty()) {
            sendFrame(Map.of("action", "unsubscribe", channel, symbols));
        }
    }

    private void sendSubscribe(String channel, Set<String> symbols) {
        if (!symbols.isEmpty()) {
            sendFrame(Map.of("action", "subscribe", channel, symbols));
        }
    }

    private void sendFrame(Map<String, Object> frame) {
        WebSocketSession s = session;
        if (s != null && s.isOpen()) {
            sendRaw(s, frame);
        }
    }

    private void sendRaw(WebSocketSession s, Map<String, Object> frame) {
        try {
            s.sendMessage(new TextMessage(objectMapper.writeValueAsString(frame)));
        } catch (RuntimeException | IOException e) {
            log.warn("Failed to send stream frame {}", frame.get("action"), e);
        }
    }

    private void scheduleReconnect() {
        if (!shouldRun) {
            return;
        }
        int attempt = ++reconnectAttempts;
        long backoffMs = Math.min(MAX_BACKOFF.toMillis(), 1000L * (1L << Math.min(attempt, 5)));
        long jitterMs = (long) (backoffMs * 0.2 * Math.random());
        log.info("Reconnecting in {}ms (attempt {})", backoffMs + jitterMs, attempt);
        scheduler.schedule(this::connect, backoffMs + jitterMs, TimeUnit.MILLISECONDS);
    }

    private void startWatchdog() {
        watchdogTask = scheduler.scheduleAtFixedRate(this::checkIdle,
                WATCHDOG_INTERVAL.toSeconds(), WATCHDOG_INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    private void stopWatchdog() {
        ScheduledFuture<?> task = watchdogTask;
        if (task != null) {
            task.cancel(false);
        }
    }

    private void checkIdle() {
        if (Duration.between(lastMessageAt, Instant.now()).compareTo(IDLE_TIMEOUT) > 0) {
            log.warn("No stream message for {}s — forcing reconnect", IDLE_TIMEOUT.toSeconds());
            closeSession();
            if (shouldRun) {
                scheduleReconnect();
            }
        }
    }

    private synchronized void closeSession() {
        WebSocketSession s = session;
        session = null;
        authenticated = false;
        stopWatchdog();
        if (s != null && s.isOpen()) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }

    @PreDestroy
    void shutdown() {
        shouldRun = false;
        closeSession();
        scheduler.shutdownNow();
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "alpaca-stream-scheduler");
            thread.setDaemon(true);
            return thread;
        };
    }
}
