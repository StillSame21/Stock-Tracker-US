package com.stocktracker.notify;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Picks up {@code PENDING} notifications and delivers them — the read side
 * of the outbox pattern the alert engine writes into (Step 5).
 *
 * <p>{@code SELECT ... FOR UPDATE SKIP LOCKED} (in {@link
 * NotificationRepository#lockNextPending}) means multiple instances polling
 * concurrently never double-send the same row — each locks a disjoint batch.
 *
 * <p>Retries up to 3 times with exponential backoff (via {@code
 * next_attempt_at}), then marks the row {@code DEAD} rather than retrying
 * forever. A per-user rate limit (20 sent/hour) is a safety net against a
 * bug in the alert engine causing a notification storm — over the limit,
 * the row is deferred rather than burning a retry attempt.
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 3;
    private static final int RATE_LIMIT_PER_HOUR = 20;
    private static final Duration RATE_LIMIT_DEFER = Duration.ofMinutes(5);

    private final NotificationRepository repository;
    private final Map<String, NotificationChannel> channelsByName;
    private final Clock clock;

    public OutboxPoller(NotificationRepository repository, List<NotificationChannel> channels, Clock clock) {
        this.repository = repository;
        this.channelsByName = channels.stream()
                .collect(java.util.stream.Collectors.toMap(NotificationChannel::name, Function.identity()));
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void poll() {
        for (Notification notification : repository.lockNextPending(BATCH_SIZE)) {
            processOne(notification);
        }
    }

    private void processOne(Notification notification) {
        long sentInLastHour = repository.countSentSince(notification.getUserId(),
                clock.instant().minus(Duration.ofHours(1)));
        if (sentInLastHour >= RATE_LIMIT_PER_HOUR) {
            notification.setNextAttemptAt(clock.instant().plus(RATE_LIMIT_DEFER));
            repository.save(notification);
            log.warn("User {} hit the {}/hour notification rate limit — deferring notification {}",
                    notification.getUserId(), RATE_LIMIT_PER_HOUR, notification.getId());
            return;
        }

        NotificationChannel channel = channelsByName.get(notification.getChannel());
        if (channel == null) {
            markDead(notification, "no channel registered for '" + notification.getChannel() + "'");
            return;
        }

        try {
            channel.send(notification);
            notification.setStatus("SENT");
            notification.setSentAt(clock.instant());
            repository.save(notification);
        } catch (Exception e) {
            notification.incrementAttempt();
            if (notification.getAttempt() >= MAX_ATTEMPTS) {
                markDead(notification, e.getMessage());
            } else {
                notification.setNextAttemptAt(clock.instant().plus(backoff(notification.getAttempt())));
                repository.save(notification);
                log.warn("Notification {} attempt {} failed, retrying: {}",
                        notification.getId(), notification.getAttempt(), e.getMessage());
            }
        }
    }

    private void markDead(Notification notification, String reason) {
        notification.setStatus("DEAD");
        repository.save(notification);
        log.warn("Notification {} marked DEAD after {} attempts: {}",
                notification.getId(), notification.getAttempt(), reason);
    }

    private static Duration backoff(int attempt) {
        return Duration.ofSeconds(Math.min(60, 5L * (1L << attempt)));
    }
}
