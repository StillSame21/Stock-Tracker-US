package com.stocktracker.gateway.alpaca;

import java.util.List;
import java.util.Set;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.stocktracker.gateway.AlpacaResilience;
import com.stocktracker.gateway.AssetDirectory;
import com.stocktracker.symbol.AssetInfo;

/**
 * Wraps {@code GET /v2/assets}. Filters to the exchanges IEX actually prints
 * data for — OTC symbols appear in the asset list but frequently have no
 * IEX data (L2.1), so letting them through would let users create alerts
 * that can never fire.
 */
@Component
public class AlpacaAssetsClient implements AssetDirectory {

    private static final Set<String> SUPPORTED_EXCHANGES = Set.of("NYSE", "NASDAQ", "AMEX", "ARCA", "BATS");

    private static final ParameterizedTypeReference<List<AlpacaAsset>> ASSET_LIST =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient tradingRestClient;
    private final AlpacaResilience resilience;

    public AlpacaAssetsClient(RestClient alpacaTradingRestClient, AlpacaResilience resilience) {
        this.tradingRestClient = alpacaTradingRestClient;
        this.resilience = resilience;
    }

    @Override
    public List<AssetInfo> fetchActiveUsEquities() {
        List<AlpacaAsset> assets = resilience.call(() -> tradingRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v2/assets")
                        .queryParam("status", "active")
                        .queryParam("asset_class", "us_equity")
                        .build())
                .retrieve()
                .body(ASSET_LIST));

        if (assets == null) {
            return List.of();
        }
        return assets.stream()
                .filter(a -> SUPPORTED_EXCHANGES.contains(a.exchange()))
                .map(a -> new AssetInfo(a.id(), a.symbol(), a.name(), a.exchange(), a.tradable(), a.fractionable()))
                .toList();
    }
}
