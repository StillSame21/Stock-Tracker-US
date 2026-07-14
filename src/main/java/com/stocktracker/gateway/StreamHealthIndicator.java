package com.stocktracker.gateway;

import java.time.Duration;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * A silently-dead ingestor is the worst failure mode — it looks exactly
 * like "no alerts triggered" (Step 8 task 2: "alert on your alerting").
 * This only detects the condition and surfaces it on {@code
 * /actuator/health}; wiring an actual page/Slack/PagerDuty alert on top of
 * that is an external monitoring concern this environment has no
 * credentials for — see the handoff notes.
 */
@Component
@Profile("!replay")
public class StreamHealthIndicator implements HealthIndicator {

    private static final Duration MAX_ACCEPTABLE_DOWNTIME = Duration.ofMinutes(5);

    private final AlpacaStreamClient streamClient;
    private final MarketClockService marketClock;

    public StreamHealthIndicator(AlpacaStreamClient streamClient, MarketClockService marketClock) {
        this.streamClient = streamClient;
        this.marketClock = marketClock;
    }

    @Override
    public Health health() {
        if (!marketClock.getClock().open()) {
            return Health.up().withDetail("reason", "market closed").build();
        }
        double downtimeSeconds = streamClient.currentDowntimeSeconds();
        if (downtimeSeconds > MAX_ACCEPTABLE_DOWNTIME.toSeconds()) {
            return Health.down()
                    .withDetail("downtimeSeconds", downtimeSeconds)
                    .withDetail("reason", "Alpaca stream has been disconnected during market hours "
                            + "for longer than " + MAX_ACCEPTABLE_DOWNTIME.toMinutes() + " minutes")
                    .build();
        }
        return Health.up().build();
    }
}
