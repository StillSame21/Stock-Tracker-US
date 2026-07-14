package com.stocktracker.stream;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Internal fan-out from the stream ingestor to everything that cares about
 * prices (alert engine, quote cache, UI broadcaster). A thin wrapper over
 * Spring's {@link ApplicationEventPublisher} — sufficient for v1's single
 * process; if the ingestor ever needs to fan out across processes this is
 * the seam where a real message bus would replace it.
 */
@Component
public class SymbolPriceBus {

    private final ApplicationEventPublisher publisher;

    public SymbolPriceBus(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void publish(BarEvent event) {
        publisher.publishEvent(event);
    }

    public void publish(TradeEvent event) {
        publisher.publishEvent(event);
    }
}
