package com.stocktracker.notify;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.stereotype.Component;

import com.stocktracker.user.User;
import com.stocktracker.user.UserRepository;

/**
 * POSTs the raw notification payload JSON to the user's configured webhook
 * URL. Uses the JDK's own {@link HttpClient} rather than Spring's
 * {@code RestClient} — that's deliberately reserved for the gateway package
 * (Step 1's ArchUnit boundary), and a generic outbound webhook has no
 * Alpaca-specific reason to route through it.
 */
@Component
public class WebhookChannel implements NotificationChannel {

    private final UserRepository userRepository;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public WebhookChannel(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public String name() {
        return "WEBHOOK";
    }

    @Override
    public void send(Notification notification) throws NotificationSendException {
        User user = userRepository.findById(notification.getUserId())
                .orElseThrow(() -> new NotificationSendException("Unknown user " + notification.getUserId()));
        String url = user.getWebhookUrl();
        if (url == null || url.isBlank()) {
            throw new NotificationSendException("User " + user.getId() + " has no webhook_url configured");
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(notification.getPayload()))
                    .build();
        } catch (IllegalArgumentException e) {
            throw new NotificationSendException("Invalid webhook_url " + url, e);
        }

        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 300) {
                throw new NotificationSendException("Webhook POST to " + url + " returned HTTP " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new NotificationSendException("Webhook POST to " + url + " failed", e);
        }
    }
}
