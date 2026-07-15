package com.stocktracker.gateway;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

/**
 * Alpaca allows exactly one concurrent WebSocket connection per account
 * (C3). This is the guard: only the process holding the {@code
 * alpaca-stream-leader} row in the {@code shedlock} table is allowed to
 * open that connection. Every other instance serves HTTP and consumes the
 * price bus, but stays a follower.
 *
 * <p>Ticks every 15s: on the first tick it tries to acquire the lock: if
 * successful, publish {@link LeadershipAcquiredEvent} and switch to
 * extending that same lock on every subsequent tick. If extension ever
 * fails (row taken by someone else, or this process was too slow), publish
 * {@link LeadershipLostEvent} and go back to trying to acquire — a follower
 * that's still ticking will pick up leadership within one or two ticks of
 * the previous leader dying.
 */
@Component
@Profile("!replay")
public class LeaderElection implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(LeaderElection.class);
    private static final String LOCK_NAME = "alpaca-stream-leader";
    private static final Duration LOCK_AT_MOST_FOR = Duration.ofSeconds(45);
    private static final Duration LOCK_AT_LEAST_FOR = Duration.ofSeconds(5);

    private final LockProvider lockProvider;
    private final ApplicationEventPublisher eventPublisher;
    private volatile SimpleLock currentLock;
    private volatile boolean running;

    public LeaderElection(LockProvider lockProvider, ApplicationEventPublisher eventPublisher) {
        this.lockProvider = lockProvider;
        this.eventPublisher = eventPublisher;
    }

    public boolean isLeader() {
        return currentLock != null;
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 2_000)
    public void tick() {
        if (!running) {
            return;
        }
        if (currentLock == null) {
            tryAcquire();
        } else {
            tryExtend();
        }
    }

    private void tryAcquire() {
        LockConfiguration config = new LockConfiguration(Instant.now(), LOCK_NAME, LOCK_AT_MOST_FOR, LOCK_AT_LEAST_FOR);
        Optional<SimpleLock> lock = lockProvider.lock(config);
        if (lock.isPresent()) {
            currentLock = lock.get();
            log.info("Acquired stream leadership");
            eventPublisher.publishEvent(new LeadershipAcquiredEvent());
        } else {
            log.debug("Not leader — another instance holds the stream lock");
        }
    }

    private void tryExtend() {
        try {
            Optional<SimpleLock> extended = currentLock.extend(LOCK_AT_MOST_FOR, LOCK_AT_LEAST_FOR);
            if (extended.isPresent()) {
                currentLock = extended.get();
            } else {
                loseLeadership("lock extension was refused");
            }
        } catch (UnsupportedOperationException e) {
            // This provider's SimpleLock doesn't support extend(); the lock will simply
            // expire at LOCK_AT_MOST_FOR and be re-acquired on the next tick, causing a
            // brief reconnect cycle rather than a crash.
            log.warn("Lock provider does not support extend() — leadership will cycle every {}", LOCK_AT_MOST_FOR);
        }
    }

    private void loseLeadership(String reason) {
        log.warn("Lost stream leadership: {}", reason);
        currentLock = null;
        eventPublisher.publishEvent(new LeadershipLostEvent());
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        SimpleLock lock = currentLock;
        currentLock = null;
        if (lock != null) {
            lock.unlock();
            eventPublisher.publishEvent(new LeadershipLostEvent());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
