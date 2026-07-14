package com.stocktracker.symbol;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Triggers the initial asset sync on boot. Skipped under {@code replay} —
 * that profile is explicitly zero-network (SETUP.md §8).
 */
@Component
@Profile("!replay")
public class SymbolStartupRunner implements ApplicationRunner {

    private final AssetSyncService syncService;

    public SymbolStartupRunner(AssetSyncService syncService) {
        this.syncService = syncService;
    }

    @Override
    public void run(ApplicationArguments args) {
        syncService.syncAtStartupBestEffort();
    }
}
