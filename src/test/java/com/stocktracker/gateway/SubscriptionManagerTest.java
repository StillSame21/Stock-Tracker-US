package com.stocktracker.gateway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionManagerTest {

    private record Call(String action, String channel, Set<String> symbols) {
    }

    private final List<Call> calls = new ArrayList<>();

    private final SubscriptionManager manager = new SubscriptionManager();

    private void attachRecordingSubscriber() {
        manager.setSubscriber(new StreamSubscriber() {
            @Override
            public void subscribe(String channel, Set<String> symbols) {
                calls.add(new Call("subscribe", channel, symbols));
            }

            @Override
            public void unsubscribe(String channel, Set<String> symbols) {
                calls.add(new Call("unsubscribe", channel, symbols));
            }
        });
    }

    @Test
    void addingInterestSendsOnlyTheIncrementalDiff() {
        attachRecordingSubscriber();

        manager.updateBarInterest("watchlist-1", Set.of("AAPL", "MSFT"));
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).action()).isEqualTo("subscribe");
        assertThat(calls.get(0).symbols()).containsExactlyInAnyOrder("AAPL", "MSFT");

        calls.clear();
        manager.updateBarInterest("watchlist-2", Set.of("MSFT", "TSLA"));
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).action()).isEqualTo("subscribe");
        assertThat(calls.get(0).symbols()).containsExactly("TSLA"); // MSFT already subscribed, not resent
    }

    @Test
    void removingInterestUnsubscribesOnlySymbolsNoLongerWanted() {
        attachRecordingSubscriber();
        manager.updateBarInterest("watchlist-1", Set.of("AAPL", "MSFT"));
        calls.clear();

        manager.updateBarInterest("watchlist-1", Set.of("AAPL")); // dropped MSFT

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).action()).isEqualTo("unsubscribe");
        assertThat(calls.get(0).symbols()).containsExactly("MSFT");
    }

    @Test
    void unsubscribingLastAlertOnASymbolRemovesItFromTrades() {
        attachRecordingSubscriber();
        manager.updateAlertCounts(Map.of("AAPL", 3));
        calls.clear();

        manager.updateAlertCounts(Map.of());

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).action()).isEqualTo("unsubscribe");
        assertThat(calls.get(0).channel()).isEqualTo("trades");
        assertThat(calls.get(0).symbols()).containsExactly("AAPL");
    }

    @Test
    void tradeChannelIsCappedAtThirtyRankedByAlertCount() {
        attachRecordingSubscriber();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < 40; i++) {
            counts.put("SYM" + i, i); // higher index = more alerts = higher rank
        }

        manager.updateAlertCounts(counts);

        assertThat(manager.desiredTradeSymbols()).hasSize(30);
        assertThat(manager.desiredTradeSymbols()).contains("SYM39", "SYM10");
        assertThat(manager.desiredTradeSymbols()).doesNotContain("SYM0", "SYM9");
    }

    @Test
    void barsChannelHasNoCap() {
        attachRecordingSubscriber();
        Set<String> many = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) {
            many.add("SYM" + i);
        }

        manager.updateBarInterest("big-watchlist", many);

        assertThat(manager.desiredBarSymbols()).hasSize(200);
    }

    @Test
    void desiredStateIsAvailableEvenWithoutASubscriberAttached() {
        // A fresh connection reads desired state directly rather than relying on diffs.
        manager.updateBarInterest("watchlist-1", Set.of("AAPL"));
        assertThat(manager.desiredBarSymbols()).containsExactly("AAPL");
    }
}
