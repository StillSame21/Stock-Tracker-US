package com.stocktracker.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "alpaca")
public record AlpacaProperties(
        String keyId,
        String secretKey,
        String dataUrl,
        String tradingUrl,
        String streamUrl,
        String feed,
        int rateLimitPerMinute,
        String streamMode,
        String replayFile
) {
    public AlpacaProperties {
        if (feed == null || feed.isBlank()) {
            feed = "iex";
        }
        if (rateLimitPerMinute <= 0) {
            rateLimitPerMinute = 180;
        }
        if (streamMode == null || streamMode.isBlank()) {
            streamMode = "live";
        }
    }
}
