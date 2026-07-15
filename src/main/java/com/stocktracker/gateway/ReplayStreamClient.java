package com.stocktracker.gateway;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.stocktracker.stream.SymbolPriceBus;

import tools.jackson.databind.ObjectMapper;

/**
 * Zero-network stand-in for {@link AlpacaStreamClient}: reads the committed
 * WS fixture (SETUP.md §5) and replays each captured frame through the same
 * {@link AlpacaFrameParser} the live client uses, so every downstream
 * consumer (alert engine, quote cache, UI broadcaster) is exercised
 * identically whether the source is live or replayed.
 *
 * <p>Replay cadence is a fixed short delay between lines rather than
 * reconstructing the original inter-message gaps — simpler, and "faster
 * than real time" is all the acceptance criteria ask for.
 */
@Component
@Profile("replay")
public class ReplayStreamClient {

    private static final Logger log = LoggerFactory.getLogger(ReplayStreamClient.class);
    private static final long DELAY_MS_BETWEEN_LINES = 10;

    private final ResourceLoader resourceLoader;
    private final AlpacaFrameParser frameParser;
    private final AlpacaProperties properties;

    public ReplayStreamClient(ResourceLoader resourceLoader, AlpacaProperties properties,
                               ObjectMapper objectMapper, SymbolPriceBus priceBus) {
        this.resourceLoader = resourceLoader;
        this.properties = properties;
        this.frameParser = new AlpacaFrameParser(objectMapper, priceBus);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void playOnStartup() {
        String location = properties.replayFile();
        if (location == null || location.isBlank()) {
            log.warn("No alpaca.replay-file configured — replay profile active but nothing to play");
            return;
        }
        CompletableFuture.runAsync(() -> play(location), Executors.newSingleThreadExecutor(daemonThreadFactory()));
    }

    private void play(String location) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            log.warn("Replay fixture not found at {} — capture one per SETUP.md §5 "
                    + "(src/test/resources/fixtures/README.md has the steps). Nothing will stream.", location);
            return;
        }
        int lines = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    frameParser.handle(line);
                    lines++;
                }
                Thread.sleep(DELAY_MS_BETWEEN_LINES);
            }
            log.info("Replay finished: {} frames from {}", lines, location);
        } catch (IOException e) {
            log.warn("Failed reading replay fixture {}", location, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "replay-stream");
            thread.setDaemon(true);
            return thread;
        };
    }
}
