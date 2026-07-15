package com.stocktracker.notify;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stocktracker.user.User;
import com.stocktracker.user.UserRepository;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookChannelTest {

    private WireMockServer wireMock;
    private final UserRepository userRepository = mock(UserRepository.class);

    @BeforeEach
    void startWireMock() {
        wireMock = new WireMockServer(0);
        wireMock.start();
    }

    @AfterEach
    void stopWireMock() {
        wireMock.stop();
    }

    private User userWithWebhook(UUID id, String webhookUrl) {
        User user = new User("trader@example.com", "Asia/Kuala_Lumpur");
        user.setWebhookUrl(webhookUrl);
        return user;
    }

    @Test
    void postsPayloadToUsersConfiguredWebhookUrl() throws Exception {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithWebhook(userId, wireMock.baseUrl() + "/hook")));
        wireMock.stubFor(post(urlPathEqualTo("/hook"))
                .withRequestBody(equalToJson("{\"symbol\":\"AAPL\"}"))
                .willReturn(aResponse().withStatus(200)));

        Notification notification = new Notification(UUID.randomUUID(), userId, "WEBHOOK", "{\"symbol\":\"AAPL\"}");
        new WebhookChannel(userRepository).send(notification);

        wireMock.verify(1, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlPathEqualTo("/hook"))
                .withHeader("Content-Type", equalTo("application/json")));
    }

    @Test
    void throwsWhenUserHasNoWebhookConfigured() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithWebhook(userId, null)));
        Notification notification = new Notification(UUID.randomUUID(), userId, "WEBHOOK", "{}");

        assertThatThrownBy(() -> new WebhookChannel(userRepository).send(notification))
                .isInstanceOf(NotificationSendException.class)
                .hasMessageContaining("no webhook_url");
    }

    @Test
    void throwsOnNon2xxResponse() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithWebhook(userId, wireMock.baseUrl() + "/hook")));
        wireMock.stubFor(post(urlPathEqualTo("/hook")).willReturn(aResponse().withStatus(500)));
        Notification notification = new Notification(UUID.randomUUID(), userId, "WEBHOOK", "{}");

        assertThatThrownBy(() -> new WebhookChannel(userRepository).send(notification))
                .isInstanceOf(NotificationSendException.class)
                .hasMessageContaining("500");
    }
}
