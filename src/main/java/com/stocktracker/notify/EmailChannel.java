package com.stocktracker.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.stocktracker.user.User;
import com.stocktracker.user.UserRepository;

/**
 * PLACEHOLDER — logs what would be sent instead of actually delivering
 * email. There is no SMTP/SendGrid/SES account or credential available in
 * this environment to wire a real sender against (SETUP.md never provisions
 * one — L6.2 in the plan already flags email deliverability as its own
 * project). This satisfies the {@link NotificationChannel} contract and the
 * outbox/retry machinery around it, but every "EMAIL" notification will be
 * marked {@code SENT} without a human ever receiving anything.
 *
 * <p><b>Before relying on this in anything but local development:</b> pick a
 * provider, add its dependency and credentials (as env vars, per SETUP.md's
 * pattern — never in {@code application.yml}), and replace the body of
 * {@link #send} with a real API call. The {@code (symbol, condition,
 * threshold, actual price, bar timestamp)} data needed for the template is
 * already in {@code Notification.payload} — see {@code AlertEvaluator}'s
 * {@code buildPayload}. Rendering the bar timestamp in the user's timezone
 * (L6's stated requirement) additionally needs {@code User.getTz()}, not yet
 * wired into the payload — do that when a real provider goes in.
 */
@Component
public class EmailChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailChannel.class);

    private final UserRepository userRepository;

    public EmailChannel(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public String name() {
        return "EMAIL";
    }

    @Override
    public void send(Notification notification) throws NotificationSendException {
        User user = userRepository.findById(notification.getUserId())
                .orElseThrow(() -> new NotificationSendException("Unknown user " + notification.getUserId()));
        log.info("[EMAIL STUB — no real provider configured] Would send to {}: {}",
                user.getEmail(), notification.getPayload());
    }
}
