package com.stocktracker.api;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import com.stocktracker.gateway.SubscriptionManager;

import static org.assertj.core.api.Assertions.assertThat;

/** Step 7 acceptance: the last viewer leaving a symbol unsubscribes it upstream (L7.1). */
class UiSessionSubscriptionServiceTest {

    private final SubscriptionManager subscriptionManager = new SubscriptionManager();
    private final UiSessionSubscriptionService service = new UiSessionSubscriptionService(subscriptionManager);

    private static Message<byte[]> subscribeMessage(String sessionId, String subscriptionId, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId(sessionId);
        accessor.setSubscriptionId(subscriptionId);
        accessor.setDestination(destination);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void firstSubscriberAddsSymbolLastUnsubscribeRemovesIt() {
        service.onSubscribe(new SessionSubscribeEvent(this, subscribeMessage("s1", "sub-0", "/topic/quotes/AAPL")));
        assertThat(subscriptionManager.desiredBarSymbols()).contains("AAPL");

        service.onUnsubscribe(new SessionUnsubscribeEvent(this, subscribeMessage("s1", "sub-0", "/topic/quotes/AAPL")));
        assertThat(subscriptionManager.desiredBarSymbols()).doesNotContain("AAPL");
    }

    @Test
    void symbolStaysSubscribedWhileAnyViewerRemains() {
        service.onSubscribe(new SessionSubscribeEvent(this, subscribeMessage("s1", "sub-0", "/topic/quotes/AAPL")));
        service.onSubscribe(new SessionSubscribeEvent(this, subscribeMessage("s2", "sub-0", "/topic/quotes/AAPL")));

        service.onUnsubscribe(new SessionUnsubscribeEvent(this, subscribeMessage("s1", "sub-0", "/topic/quotes/AAPL")));
        assertThat(subscriptionManager.desiredBarSymbols()).contains("AAPL"); // s2 still watching

        service.onUnsubscribe(new SessionUnsubscribeEvent(this, subscribeMessage("s2", "sub-0", "/topic/quotes/AAPL")));
        assertThat(subscriptionManager.desiredBarSymbols()).doesNotContain("AAPL");
    }

    @Test
    void disconnectReleasesAllOfThatSessionsSubscriptions() {
        service.onSubscribe(new SessionSubscribeEvent(this, subscribeMessage("s1", "sub-0", "/topic/quotes/AAPL")));
        service.onSubscribe(new SessionSubscribeEvent(this, subscribeMessage("s1", "sub-1", "/topic/quotes/MSFT")));

        StompHeaderAccessor disconnectAccessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        disconnectAccessor.setSessionId("s1");
        Message<byte[]> disconnectMessage = MessageBuilder.createMessage(new byte[0], disconnectAccessor.getMessageHeaders());
        service.onDisconnect(new SessionDisconnectEvent(this, disconnectMessage, "s1", CloseStatus.NORMAL));

        assertThat(subscriptionManager.desiredBarSymbols()).isEmpty();
    }

    @Test
    void nonQuoteDestinationsAreIgnored() {
        service.onSubscribe(new SessionSubscribeEvent(this, subscribeMessage("s1", "sub-0", "/topic/other")));
        assertThat(subscriptionManager.desiredBarSymbols()).isEmpty();
    }
}
