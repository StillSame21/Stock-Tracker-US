package com.stocktracker.gateway;

import java.util.Set;

/** Sink for incremental subscription changes, implemented by whatever holds the live connection. */
public interface StreamSubscriber {
    void subscribe(String channel, Set<String> symbols);

    void unsubscribe(String channel, Set<String> symbols);
}
