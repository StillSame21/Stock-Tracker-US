package com.stocktracker.gateway;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.stocktracker.gateway.alpaca.AlpacaSnapshotClient;
import com.stocktracker.quote.Quote;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AlpacaMarketDataProviderTest {

    private WireMockServer wireMock;

    @BeforeEach
    void startWireMock() {
        wireMock = new WireMockServer(0);
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
    }

    @AfterEach
    void stopWireMock() {
        wireMock.stop();
    }

    private AlpacaMarketDataProvider newProvider() {
        AlpacaProperties properties = new AlpacaProperties("key", "secret",
                wireMock.baseUrl(), wireMock.baseUrl(), "wss://unused", "iex", 180, "live", null);
        RestClient restClient = RestClient.builder().baseUrl(properties.dataUrl())
                .defaultHeader("APCA-API-KEY-ID", properties.keyId())
                .defaultHeader("APCA-API-SECRET-KEY", properties.secretKey())
                .build();
        AlpacaResilience resilience = new AlpacaResilience(properties);
        AlpacaSnapshotClient snapshotClient = new AlpacaSnapshotClient(restClient, resilience, properties);
        return new AlpacaMarketDataProvider(snapshotClient, properties);
    }

    @Test
    void happyPathReturnsQuoteForEachSymbol() {
        wireMock.stubFor(get(urlPathEqualTo("/v2/stocks/snapshots")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {
                          "AAPL": {
                            "latestTrade": {"t": "2026-07-14T15:30:00Z", "p": 150.25, "s": 100},
                            "dailyBar": {"t": "2026-07-14T15:30:00Z", "o": 149.0, "h": 151.0, "l": 148.5, "c": 150.25, "v": 1000000},
                            "prevDailyBar": {"t": "2026-07-13T20:00:00Z", "o": 148.0, "h": 149.5, "l": 147.5, "c": 148.75, "v": 900000}
                          },
                          "MSFT": {
                            "latestTrade": {"t": "2026-07-14T15:30:00Z", "p": 310.10, "s": 50},
                            "dailyBar": {"t": "2026-07-14T15:30:00Z", "o": 305.0, "h": 311.0, "l": 304.0, "c": 310.10, "v": 500000},
                            "prevDailyBar": {"t": "2026-07-13T20:00:00Z", "o": 300.0, "h": 306.0, "l": 299.0, "c": 305.0, "v": 400000}
                          }
                        }
                        """)));

        Map<String, Quote> quotes = newProvider().getQuotes(Set.of("AAPL", "MSFT"));

        assertThat(quotes).hasSize(2);
        assertThat(quotes.get("AAPL").lastPrice()).isEqualByComparingTo("150.25");
        assertThat(quotes.get("AAPL").feed()).isEqualTo("iex");
        assertThat(quotes.get("MSFT").lastPrice()).isEqualByComparingTo("310.10");
    }

    @Test
    void marketClosedFallsBackToPrevDailyBar() {
        wireMock.stubFor(get(urlPathEqualTo("/v2/stocks/snapshots")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {
                          "F": {
                            "prevDailyBar": {"t": "2026-07-13T20:00:00Z", "o": 12.0, "h": 12.5, "l": 11.8, "c": 12.10, "v": 300000}
                          }
                        }
                        """)));

        Quote quote = newProvider().getQuote("F");

        assertThat(quote.lastPrice()).isEqualByComparingTo("12.10");
        assertThat(quote.stale()).isTrue();
    }

    @Test
    void serverErrorWithNoCacheThrowsQuoteUnavailable() {
        wireMock.stubFor(get(urlPathEqualTo("/v2/stocks/snapshots")).willReturn(aResponse().withStatus(500)));

        AlpacaMarketDataProvider provider = newProvider();

        assertThrows(QuoteUnavailableException.class, () -> provider.getQuote("AAPL"));
    }

    @Test
    void malformedJsonThrowsQuoteUnavailable() {
        wireMock.stubFor(get(urlPathEqualTo("/v2/stocks/snapshots")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{ not valid json")));

        AlpacaMarketDataProvider provider = newProvider();

        assertThrows(QuoteUnavailableException.class, () -> provider.getQuote("AAPL"));
    }

    @Test
    void emptySymbolSetReturnsEmptyMapWithoutCallingUpstream() {
        AlpacaMarketDataProvider provider = newProvider();

        Map<String, Quote> quotes = provider.getQuotes(Set.of());

        assertThat(quotes).isEmpty();
        wireMock.verify(0, WireMock.getRequestedFor(urlPathEqualTo("/v2/stocks/snapshots")));
    }

    @Test
    void retriesOn429ThenSucceeds() {
        wireMock.stubFor(get(urlPathEqualTo("/v2/stocks/snapshots"))
                .inScenario("rate-limit-then-ok")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(429))
                .willSetStateTo("recovered"));
        wireMock.stubFor(get(urlPathEqualTo("/v2/stocks/snapshots"))
                .inScenario("rate-limit-then-ok")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"AAPL": {"latestTrade": {"t": "2026-07-14T15:30:00Z", "p": 150.25, "s": 100}}}
                        """)));

        Quote quote = newProvider().getQuote("AAPL");

        assertThat(quote.lastPrice()).isEqualByComparingTo("150.25");
    }

    @Test
    void unknownSymbolAbsentFromResponseIsSimplyNotInResult() {
        wireMock.stubFor(get(urlPathEqualTo("/v2/stocks/snapshots")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{}")));

        Map<String, Quote> quotes = newProvider().getQuotes(Set.of("ZZZZZ"));

        assertThat(quotes).isEmpty();
    }
}
