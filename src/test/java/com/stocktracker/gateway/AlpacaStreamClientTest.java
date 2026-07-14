package com.stocktracker.gateway;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;

import com.stocktracker.stream.SymbolPriceBus;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlpacaStreamClientTest {

    private final AlpacaProperties properties = new AlpacaProperties("key123", "secret456",
            "https://data.example", "https://trading.example", "wss://stream.example/v2/iex", "iex", 180, "live", null);

    private final WebSocketClient mockWebSocketClient = mock(WebSocketClient.class);
    private final WebSocketSession mockSession = mock(WebSocketSession.class);
    private final SubscriptionManager subscriptionManager = new SubscriptionManager();

    private AlpacaStreamClient newClient() {
        when(mockWebSocketClient.execute(any(), any(WebSocketHttpHeaders.class), any(URI.class)))
                .thenReturn(CompletableFuture.completedFuture(mockSession));
        when(mockSession.isOpen()).thenReturn(true);
        SymbolPriceBus priceBus = new SymbolPriceBus(event -> { });
        return new AlpacaStreamClient(mockWebSocketClient, properties, subscriptionManager, priceBus,
                new ObjectMapper(), new SimpleMeterRegistry());
    }

    @Test
    void connectsAuthenticatesThenSendsFullSubscribeState() throws Exception {
        subscriptionManager.updateBarInterest("watchlist-1", Set.of("AAPL"));
        AlpacaStreamClient client = newClient();

        client.onLeadershipAcquired(new LeadershipAcquiredEvent());
        client.afterConnectionEstablished(mockSession);

        ArgumentCaptor<TextMessage> firstFrame = ArgumentCaptor.forClass(TextMessage.class);
        verify(mockSession, times(1)).sendMessage(firstFrame.capture());
        assertThat(firstFrame.getValue().getPayload()).contains("\"action\":\"auth\"");

        client.handleMessage(mockSession, new TextMessage("[{\"T\":\"success\",\"msg\":\"authenticated\"}]"));

        ArgumentCaptor<TextMessage> allFrames = ArgumentCaptor.forClass(TextMessage.class);
        verify(mockSession, times(2)).sendMessage(allFrames.capture());
        String subscribeFrame = allFrames.getAllValues().get(1).getPayload();
        assertThat(subscribeFrame).contains("\"action\":\"subscribe\"").contains("AAPL");
    }

    @Test
    void leadershipLostClosesTheConnection() throws Exception {
        AlpacaStreamClient client = newClient();
        client.onLeadershipAcquired(new LeadershipAcquiredEvent());
        client.afterConnectionEstablished(mockSession);

        client.onLeadershipLost(new LeadershipLostEvent());

        verify(mockSession, times(1)).close();
    }

    @Test
    void doesNotSubscribeBeforeAuthenticationCompletes() throws Exception {
        AlpacaStreamClient client = newClient();
        client.onLeadershipAcquired(new LeadershipAcquiredEvent());
        client.afterConnectionEstablished(mockSession);

        // Interest arrives before the auth ack — must not be sent yet.
        subscriptionManager.updateBarInterest("watchlist-1", Set.of("TSLA"));

        verify(mockSession, times(1)).sendMessage(any()); // only the auth frame so far
    }
}
