package com.stocktracker.gateway.alpaca;

import java.math.BigDecimal;
import java.time.Instant;

record AlpacaTrade(
        Instant t,
        BigDecimal p,
        long s
) {
}
