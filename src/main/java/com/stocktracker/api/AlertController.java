package com.stocktracker.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stocktracker.alert.Alert;
import com.stocktracker.alert.AlertService;
import com.stocktracker.alert.Condition;

@RestController
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    public record CreateAlertRequest(UUID userId, String symbol, Condition condition, BigDecimal threshold,
                                      Integer cooldownSeconds, Boolean extendedHours) {
    }

    @PostMapping("/api/alerts")
    public Alert create(@RequestBody CreateAlertRequest request) {
        int cooldown = request.cooldownSeconds() != null ? request.cooldownSeconds() : 3600;
        boolean extendedHours = Boolean.TRUE.equals(request.extendedHours());
        return alertService.create(request.userId(), request.symbol(), request.condition(),
                request.threshold(), cooldown, extendedHours);
    }

    @GetMapping("/api/alerts")
    public List<Alert> listForUser(@RequestParam UUID userId) {
        return alertService.findByUser(userId);
    }

    @GetMapping("/api/alerts/{id}")
    public Alert get(@PathVariable UUID id) {
        return alertService.get(id);
    }

    @PutMapping("/api/alerts/{id}/status")
    public Alert setStatus(@PathVariable UUID id, @RequestBody String status) {
        return alertService.setStatus(id, status);
    }

    @DeleteMapping("/api/alerts/{id}")
    public void delete(@PathVariable UUID id) {
        alertService.delete(id);
    }
}
