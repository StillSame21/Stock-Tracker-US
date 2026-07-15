package com.stocktracker.notify;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.stocktracker.user.User;
import com.stocktracker.user.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends alert notifications over SMTP via {@link JavaMailSender}. Credentials come from
 * {@code spring.mail.*} (env vars {@code SMTP_HOST}/{@code SMTP_USERNAME}/{@code SMTP_PASSWORD},
 * per SETUP.md's pattern — never in {@code application.yml}).
 *
 * <p>{@link JavaMailSender} is injected via {@link ObjectProvider} rather than directly: Boot only
 * registers that bean when {@code spring.mail.host} is non-blank, and this channel must not prevent
 * the whole app from starting just because SMTP hasn't been configured yet — same tolerance
 * {@link WebhookChannel} already gives a user with no {@code webhook_url} set.
 */
@Component
public class EmailChannel implements NotificationChannel {

    private final UserRepository userRepository;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final ObjectMapper objectMapper;
    private final String fromAddress;

    public EmailChannel(UserRepository userRepository, ObjectProvider<JavaMailSender> mailSender,
                         ObjectMapper objectMapper,
                         @Value("${notify.email.from:alerts@stocktracker.local}") String fromAddress) {
        this.userRepository = userRepository;
        this.mailSender = mailSender;
        this.objectMapper = objectMapper;
        this.fromAddress = fromAddress;
    }

    @Override
    public String name() {
        return "EMAIL";
    }

    @Override
    public void send(Notification notification) throws NotificationSendException {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            throw new NotificationSendException("SMTP not configured — set SMTP_HOST/SMTP_USERNAME/SMTP_PASSWORD");
        }

        User user = userRepository.findById(notification.getUserId())
                .orElseThrow(() -> new NotificationSendException("Unknown user " + notification.getUserId()));

        JsonNode payload = objectMapper.readTree(notification.getPayload());

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(user.getEmail());
        message.setSubject("Stock alert: %s %s".formatted(
                payload.path("symbol").asString("?"), payload.path("condition").asString("?")));
        message.setText(renderBody(payload, user));

        try {
            sender.send(message);
        } catch (MailException e) {
            throw new NotificationSendException("Failed to send email to " + user.getEmail(), e);
        }
    }

    // barTimestamp is UTC on the wire; rendering it in the user's own tz is left as a follow-up —
    // needs the bar instant parsed and reformatted with ZoneId.of(user.getTz()), not just appended.
    private static String renderBody(JsonNode payload, User user) {
        return """
                %s %s

                Threshold:  %s
                Bar close:  %s (high %s / low %s)
                Bar time:   %s UTC (your timezone: %s)
                """.formatted(
                payload.path("symbol").asString("?"),
                payload.path("condition").asString("?"),
                payload.path("threshold").asString("?"),
                payload.path("barClose").asString("?"),
                payload.path("barHigh").asString("?"),
                payload.path("barLow").asString("?"),
                payload.path("barTimestamp").asString("?"),
                user.getTz());
    }
}
