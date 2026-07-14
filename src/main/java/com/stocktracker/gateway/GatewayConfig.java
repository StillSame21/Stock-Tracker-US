package com.stocktracker.gateway;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;

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

    /**
     * Alpaca negotiates permessage-deflate compression on the WS; Spring's client doesn't
     * negotiate it so frames arrive uncompressed (fine — just means larger frames than a
     * compression-aware client would see). Set an explicit max frame size rather than rely
     * on the container default, since a swapped HTTP/WS client later could change bandwidth
     * assumptions (L4.5).
     */
    @Bean
    @Profile("!replay")
    WebSocketClient alpacaWebSocketClient() {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxTextMessageBufferSize(1 << 20); // 1 MB
        return new StandardWebSocketClient(container);
    }
}
