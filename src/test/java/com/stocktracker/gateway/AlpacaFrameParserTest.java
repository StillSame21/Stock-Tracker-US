package com.stocktracker.gateway;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.stocktracker.stream.BarEvent;
import com.stocktracker.stream.SymbolPriceBus;
import com.stocktracker.stream.TradeEvent;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AlpacaFrameParserTest {

    private final List<Object> publishedEvents = new ArrayList<>();
    private final AlpacaFrameParser parser = new AlpacaFrameParser(
            new ObjectMapper(), new SymbolPriceBus(recordingPublisher()));

    private ApplicationEventPublisher recordingPublisher() {
        return publishedEvents::add;
    }

    @Test
    void parsesAllFrameTypesFromTheSyntheticFixture() throws Exception {
        List<String> lines = readFixtureLines();

        String lastSuccessMessage = null;
        for (String line : lines) {
            String result = parser.handle(line);
            if (result != null) {
                lastSuccessMessage = result;
            }
        }

        assertThat(lastSuccessMessage).isEqualTo("authenticated");

        List<BarEvent> bars = publishedEvents.stream()
                .filter(BarEvent.class::isInstance).map(BarEvent.class::cast).toList();
        List<TradeEvent> trades = publishedEvents.stream()
                .filter(TradeEvent.class::isInstance).map(TradeEvent.class::cast).toList();

        assertThat(bars).hasSize(3); // AAPL, MSFT, and one updatedBars revision for AAPL
        assertThat(bars.get(0).symbol()).isEqualTo("AAPL");
        assertThat(bars.get(0).updated()).isFalse();
        assertThat(bars.get(2).symbol()).isEqualTo("AAPL");
        assertThat(bars.get(2).updated()).isTrue();
        assertThat(bars.get(2).close()).isEqualByComparingTo("150.25");

        assertThat(trades).hasSize(2); // one trade, one cancellation
        assertThat(trades.get(0).cancelled()).isFalse();
        assertThat(trades.get(1).cancelled()).isTrue();
    }

    @Test
    void malformedFrameIsLoggedAndSkippedNotThrown() {
        String result = parser.handle("not json at all");
        assertThat(result).isNull();
        assertThat(publishedEvents).isEmpty();
    }

    private static List<String> readFixtureLines() throws Exception {
        List<String> lines = new ArrayList<>();
        try (InputStream in = AlpacaFrameParserTest.class.getClassLoader()
                .getResourceAsStream("fixtures/synthetic-sample.jsonl");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }
}
