package com.stocktracker.notify;

import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class NotificationOutboxService implements NotificationOutbox {

    private final NotificationRepository repository;

    public NotificationOutboxService(NotificationRepository repository) {
        this.repository = repository;
    }

    @Override
    public void enqueue(UUID alertId, String channel, String payloadJson) {
        repository.save(new Notification(alertId, channel, payloadJson));
    }
}
