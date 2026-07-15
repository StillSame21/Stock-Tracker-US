package com.stocktracker.gateway;

import java.util.List;

import com.stocktracker.symbol.AssetInfo;

/**
 * Vendor-neutral asset universe lookup — the {@code symbol} package depends
 * only on this interface, never on {@code gateway.alpaca} directly, so the
 * ArchUnit boundary in Step 1 holds for the asset sync path too.
 */
public interface AssetDirectory {
    List<AssetInfo> fetchActiveUsEquities();
}
