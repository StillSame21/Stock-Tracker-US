package com.stocktracker.watchlist;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stocktracker.quote.Quote;
import com.stocktracker.symbol.Symbol;
import com.stocktracker.symbol.SymbolRepository;
import com.stocktracker.symbol.SymbolValidator;
import com.stocktracker.user.User;
import com.stocktracker.user.UserRepository;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 3 acceptance criteria: loading a 50-symbol watchlist makes exactly one
 * upstream call, and a second load within the cache TTL makes zero.
 */
@SpringBootTest
@ActiveProfiles("replay")   // skips the live-network startup asset sync (Step 2)
@Testcontainers
class WatchlistQuoteBatchingIT {

    @org.testcontainers.junit.jupiter.Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(0);
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("alpaca.data-url", () -> wireMock.baseUrl());
        registry.add("alpaca.trading-url", () -> wireMock.baseUrl());
    }

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private SymbolRepository symbolRepository;
    @Autowired
    private SymbolValidator symbolValidator;
    @Autowired
    private WatchlistRepository watchlistRepository;
    @Autowired
    private WatchlistService watchlistService;

    @Test
    void fiftySymbolWatchlistMakesOneUpstreamCallThenZero() {
        User user = userRepository.save(new User("trader@example.com", "Asia/Kuala_Lumpur"));
        Watchlist watchlist = watchlistRepository.save(new Watchlist(user.getId(), "Big list"));

        Set<String> symbols = new LinkedHashSet<>();
        StringBuilder body = new StringBuilder("{");
        for (int i = 0; i < 50; i++) {
            String ticker = "SYM" + i;
            symbols.add(ticker);
            Symbol saved = symbolRepository.save(new Symbol(UUID.randomUUID(), ticker, ticker + " Inc",
                    "NASDAQ", true, false, Instant.now()));
            watchlist.addSymbol(saved);
            if (i > 0) {
                body.append(",");
            }
            body.append("\"").append(ticker).append("\":{\"latestTrade\":{\"t\":\"2026-07-14T15:30:00Z\",\"p\":")
                    .append(10 + i).append(",\"s\":10}}");
        }
        body.append("}");
        watchlistRepository.save(watchlist);
        symbolValidator.reload();

        wireMock.stubFor(get(urlPathEqualTo("/v2/stocks/snapshots")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody(body.toString())));

        var first = watchlistService.quotesFor(watchlist.getId());
        assertThat(first).hasSize(50);
        wireMock.verify(1, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo("/v2/stocks/snapshots")));

        var second = watchlistService.quotesFor(watchlist.getId());
        assertThat(second).hasSize(50);
        wireMock.verify(1, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo("/v2/stocks/snapshots")));
    }
}
