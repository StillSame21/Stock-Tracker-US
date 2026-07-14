package com.stocktracker.symbol;

import java.util.UUID;

/** Vendor-neutral asset record, mirroring {@code Quote}'s role for prices. */
public record AssetInfo(UUID assetId, String symbol, String name, String exchange,
                         boolean tradable, boolean fractionable) {
}
