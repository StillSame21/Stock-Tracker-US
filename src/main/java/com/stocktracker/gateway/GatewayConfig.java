package com.stocktracker.gateway;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AlpacaProperties.class)
public class GatewayConfig {

    @Bean
    RestClient alpacaRestClient(AlpacaProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.dataUrl())
                .defaultHeader("APCA-API-KEY-ID", properties.keyId())
                .defaultHeader("APCA-API-SECRET-KEY", properties.secretKey())
                .build();
    }

    /** Trading API — assets, clock, calendar. Separate base URL from the data API. */
    @Bean
    RestClient alpacaTradingRestClient(AlpacaProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.tradingUrl())
                .defaultHeader("APCA-API-KEY-ID", properties.keyId())
                .defaultHeader("APCA-API-SECRET-KEY", properties.secretKey())
                .build();
    }
}
