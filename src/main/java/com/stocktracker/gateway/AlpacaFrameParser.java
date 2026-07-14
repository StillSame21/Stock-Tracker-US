package com.stocktracker.gateway;

import java.math.BigDecimal;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.stocktracker.stream.BarEvent;
import com.stocktracker.stream.SymbolPriceBus;
import com.stocktracker.stream.TradeEvent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses one raw Alpaca WS text frame — a JSON array of message objects —
 * into {@link BarEvent}/{@link TradeEvent} and publishes them. Shared by the
 * live {@link AlpacaStreamClient} and {@link ReplayStreamClient} so both
 * read exactly the same wire format.
 */
final class AlpacaFrameParser {

    private static final Logger log = LoggerFactory.getLogger(AlpacaFrameParser.class);

    private final ObjectMapper objectMapper;
    private final SymbolPriceBus priceBus;

    AlpacaFrameParser(ObjectMapper objectMapper, SymbolPriceBus priceBus) {
        this.objectMapper = objectMapper;
        this.priceBus = priceBus;
    }

    /** @return the "msg" field if this frame contained a {@code success} message, else {@code null}. */
    String handle(String payload) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (RuntimeException e) {
            log.warn("Could not parse stream frame: {}", payload, e);
            return null;
        }
        if (!root.isArray()) {
            return null;
        }

        String successMessage = null;
        for (JsonNode node : root) {
            String type = node.path("T").asString("");
            switch (type) {
                case "success" -> successMessage = node.path("msg").asString(null);
                case "error" -> log.error("Alpaca stream error: {}", node);
                case "subscription" -> log.info("Subscription ack: {}", node);
                case "b" -> handleBar(node, false);
                case "updatedBars" -> handleBar(node, true);   // L4.4: a revised bar for one already seen
                case "t" -> handleTrade(node, false);
                case "c", "x" -> handleTrade(node, true);      // L4.3: correction/cancellation
                default -> log.debug("Unhandled stream message type '{}': {}", type, node);
            }
        }
        return successMessage;
    }

    private void handleBar(JsonNode node, boolean updated) {
        BarEvent event = new BarEvent(
                node.path("S").asString(),
                parseInstant(node.path("t")),
                decimal(node.path("o")),
                decimal(node.path("h")),
                decimal(node.path("l")),
                decimal(node.path("c")),
                node.path("v").asLong(0L),
                updated);
        priceBus.publish(event);
    }

    private void handleTrade(JsonNode node, boolean cancelled) {
        if (cancelled) {
            log.info("Trade correction/cancellation for {}: {}", node.path("S").asString(), node);
        }
        // Cancellation/correction messages ("c"/"x") don't necessarily carry a price —
        // fall back to zero rather than throwing (L4.3).
        TradeEvent event = new TradeEvent(
                node.path("S").asString(),
                parseInstant(node.path("t")),
                decimal(node.path("p")),
                node.path("s").asLong(0L),
                cancelled);
        priceBus.publish(event);
    }

    private static Instant parseInstant(JsonNode node) {
        String text = node.asString(null);
        return text == null || text.isEmpty() ? null : Instant.parse(text);
    }

    // JsonNode.decimalValue() throws JsonNodeException on a MissingNode in Jackson 3
    // (unlike Jackson 2, which defaulted to BigDecimal.ZERO) — decimalValueOpt() is the
    // non-throwing equivalent.
    private static BigDecimal decimal(JsonNode node) {
        return node.decimalValueOpt().orElse(BigDecimal.ZERO);
    }
}
