package com.stocktracker.api;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stocktracker.notify.Notification;
import com.stocktracker.notify.NotificationRepository;

/** Read-only visibility into the outbox — Step 6 acceptance: a DEAD notification must be visible here. */
@RestController
public class NotificationController {

    private final NotificationRepository repository;

    public NotificationController(NotificationRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/notifications")
    public List<Notification> listForUser(@RequestParam UUID userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId);
    }
}
