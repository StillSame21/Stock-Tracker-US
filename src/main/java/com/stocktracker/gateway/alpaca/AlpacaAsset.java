package com.stocktracker.gateway.alpaca;

import java.util.UUID;

/** Raw entry from {@code GET /v2/assets}. Package-private — never leaves this package. */
record AlpacaAsset(
        UUID id,
        String symbol,
        String name,
        String exchange,
        String status,
        boolean tradable,
        boolean fractionable
) {
}
