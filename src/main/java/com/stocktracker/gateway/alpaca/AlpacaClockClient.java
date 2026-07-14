package com.stocktracker.gateway.alpaca;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.stocktracker.gateway.AlpacaResilience;
import com.stocktracker.gateway.MarketClock;

@Component
public class AlpacaClockClient {

    private final RestClient tradingRestClient;
    private final AlpacaResilience resilience;

    public AlpacaClockClient(RestClient alpacaTradingRestClient, AlpacaResilience resilience) {
        this.tradingRestClient = alpacaTradingRestClient;
        this.resilience = resilience;
    }

    public MarketClock fetchClock() {
        AlpacaClockResponse response = resilience.call(() -> tradingRestClient.get()
                .uri("/v2/clock")
                .retrieve()
                .body(AlpacaClockResponse.class));
        return new MarketClock(response.timestamp(), response.isOpen(), response.nextOpen(), response.nextClose());
    }
}
