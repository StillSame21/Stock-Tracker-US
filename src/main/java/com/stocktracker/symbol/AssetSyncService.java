package com.stocktracker.symbol;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stocktracker.gateway.AssetDirectory;

/**
 * Keeps the local {@code symbols} table in sync with Alpaca's US-equity
 * universe (Step 2). Runs once at startup and nightly at 06:00 MYT — after
 * the US session closes (04:00/05:00 MYT), per C9's timezone offset.
 *
 * <p>The universe changes daily (IPOs, delistings, ticker changes). A symbol
 * dropping out of the active list is <b>disabled</b> ({@code tradable=false}),
 * never deleted (L2.3) — deleting would orphan alert/watchlist references and
 * silently kill a user's alert without telling them.
 */
@Service
public class AssetSyncService {

    private static final Logger log = LoggerFactory.getLogger(AssetSyncService.class);

    private final AssetDirectory assetsClient;
    private final SymbolRepository repository;
    private final SymbolValidator validator;

    public AssetSyncService(AssetDirectory assetsClient, SymbolRepository repository, SymbolValidator validator) {
        this.assetsClient = assetsClient;
        this.repository = repository;
        this.validator = validator;
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Kuala_Lumpur")
    @Transactional
    public void syncNightly() {
        sync();
    }

    /**
     * Safe to call at startup: failures are logged, not thrown, so a missing
     * or invalid Alpaca key doesn't prevent the rest of the app from serving
     * traffic (e.g. the {@code replay} profile, which has no need for live
     * credentials at all).
     */
    @Transactional
    public void syncAtStartupBestEffort() {
        try {
            sync();
        } catch (RuntimeException e) {
            log.warn("Startup asset sync failed — symbol table may be empty or stale until the next "
                    + "nightly sync or a manual retry. This is expected without valid ALPACA_* credentials.", e);
        }
        // Reload from whatever is already in the table even if the fetch above failed,
        // so a previously-synced table still serves validation after a restart.
        validator.reload();
    }

    private void sync() {
        List<AssetInfo> active = assetsClient.fetchActiveUsEquities();
        Set<UUID> activeIds = new HashSet<>();
        Instant now = Instant.now();

        for (AssetInfo asset : active) {
            activeIds.add(asset.assetId());
            repository.save(new Symbol(asset.assetId(), asset.symbol(), asset.name(), asset.exchange(),
                    asset.tradable(), asset.fractionable(), now));
        }

        List<Symbol> currentlyTradable = repository.findAll().stream().filter(Symbol::isTradable).toList();
        for (Symbol existing : currentlyTradable) {
            if (!activeIds.contains(existing.getAssetId())) {
                // Delisted or moved off a supported exchange — disable, don't delete (L2.3).
                repository.save(new Symbol(existing.getAssetId(), existing.getSymbol(), existing.getName(),
                        existing.getExchange(), false, existing.isFractionable(), now));
                log.info("Disabled symbol no longer active: {}", existing.getSymbol());
            }
        }

        validator.reload();
        log.info("Asset sync complete: {} active US-equity symbols", active.size());
    }
}
