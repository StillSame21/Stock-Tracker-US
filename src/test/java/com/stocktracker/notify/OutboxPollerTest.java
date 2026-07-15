package com.stocktracker.notify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxPollerTest {

    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-14T15:00:00Z"), ZoneOffset.UTC);
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private Notification pending(String channel, int attempt) {
        Notification n = new Notification(UUID.randomUUID(), UUID.randomUUID(), channel, "{}");
        for (int i = 0; i < attempt; i++) {
            n.incrementAttempt();
        }
        return n;
    }

    @Test
    void successMarksSentAndStampsTime() {
        when(repository.countSentSince(any(), any())).thenReturn(0L);
        Notification n = pending("EMAIL", 0);
        when(repository.lockNextPending(anyInt())).thenReturn(List.of(n));
        NotificationChannel emailChannel = new NotificationChannel() {
            @Override public String name() { return "EMAIL"; }
            @Override public void send(Notification notification) { }
        };

        new OutboxPoller(repository, List.of(emailChannel), clock, meterRegistry).poll();

        assertThat(n.getStatus()).isEqualTo("SENT");
        assertThat(n.getSentAt()).isEqualTo(clock.instant());
    }

    @Test
    void failureBelowMaxAttemptsStaysPendingWithBackoff() {
        when(repository.countSentSince(any(), any())).thenReturn(0L);
        Notification n = pending("EMAIL", 0);
        when(repository.lockNextPending(anyInt())).thenReturn(List.of(n));
        NotificationChannel failing = new NotificationChannel() {
            @Override public String name() { return "EMAIL"; }
            @Override public void send(Notification notification) throws NotificationSendException {
                throw new NotificationSendException("boom");
            }
        };

        new OutboxPoller(repository, List.of(failing), clock, meterRegistry).poll();

        assertThat(n.getStatus()).isEqualTo("PENDING");
        assertThat(n.getAttempt()).isEqualTo(1);
        assertThat(n.getNextAttemptAt()).isAfter(clock.instant());
    }

    @Test
    void failureAtMaxAttemptsIsMarkedDead() {
        when(repository.countSentSince(any(), any())).thenReturn(0L);
        Notification n = pending("EMAIL", 2); // this will be the 3rd attempt
        when(repository.lockNextPending(anyInt())).thenReturn(List.of(n));
        NotificationChannel failing = new NotificationChannel() {
            @Override public String name() { return "EMAIL"; }
            @Override public void send(Notification notification) throws NotificationSendException {
                throw new NotificationSendException("boom");
            }
        };

        new OutboxPoller(repository, List.of(failing), clock, meterRegistry).poll();

        assertThat(n.getStatus()).isEqualTo("DEAD");
    }

    @Test
    void unknownChannelIsMarkedDeadImmediately() {
        when(repository.countSentSince(any(), any())).thenReturn(0L);
        Notification n = pending("SMS", 0);
        when(repository.lockNextPending(anyInt())).thenReturn(List.of(n));

        new OutboxPoller(repository, List.of(), clock, meterRegistry).poll();

        assertThat(n.getStatus()).isEqualTo("DEAD");
    }

    @Test
    void overRateLimitDefersWithoutBurningAnAttempt() {
        when(repository.countSentSince(any(), any())).thenReturn(20L);
        Notification n = pending("EMAIL", 0);
        when(repository.lockNextPending(anyInt())).thenReturn(List.of(n));
        NotificationChannel emailChannel = new NotificationChannel() {
            @Override public String name() { return "EMAIL"; }
            @Override public void send(Notification notification) {
                throw new AssertionError("should not be called when rate-limited");
            }
        };

        new OutboxPoller(repository, List.of(emailChannel), clock, meterRegistry).poll();

        assertThat(n.getStatus()).isEqualTo("PENDING");
        assertThat(n.getAttempt()).isEqualTo(0);
        assertThat(n.getNextAttemptAt()).isAfter(clock.instant());
    }
}
