package com.stocktracker.gateway.alpaca;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

import com.stocktracker.quote.Quote;

/**
 * Maps a raw {@link AlpacaSnapshot} into the vendor-neutral {@link Quote}.
 * The only place in the codebase that reads Alpaca's snapshot JSON shape.
 */
public final class AlpacaQuoteMapper {

    private AlpacaQuoteMapper() {
    }

    public static Quote map(String symbol, AlpacaSnapshot snapshot, String feed, Duration staleThreshold) {
        BigDecimal lastPrice = lastPrice(snapshot);
        Instant asOf = asOf(snapshot);
        BigDecimal prevClose = snapshot.prevDailyBar() != null ? snapshot.prevDailyBar().c() : null;
        long volume = snapshot.dailyBar() != null ? snapshot.dailyBar().v() : 0L;

        BigDecimal dailyChange = BigDecimal.ZERO;
        BigDecimal dailyChangePct = BigDecimal.ZERO;
        if (lastPrice != null && prevClose != null && prevClose.signum() != 0) {
            dailyChange = lastPrice.subtract(prevClose);
            dailyChangePct = dailyChange
                    .divide(prevClose, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }

        boolean stale = asOf == null || Duration.between(asOf, Instant.now()).compareTo(staleThreshold) > 0;

        return new Quote(symbol, lastPrice, dailyChange, dailyChangePct, volume, asOf, feed, stale);
    }

    // L1.3: outside market hours latestTrade/dailyBar may be absent — fall back to prevDailyBar.
    private static BigDecimal lastPrice(AlpacaSnapshot snapshot) {
        if (snapshot.latestTrade() != null) {
            return snapshot.latestTrade().p();
        }
        if (snapshot.dailyBar() != null) {
            return snapshot.dailyBar().c();
        }
        if (snapshot.prevDailyBar() != null) {
            return snapshot.prevDailyBar().c();
        }
        return null;
    }

    private static Instant asOf(AlpacaSnapshot snapshot) {
        if (snapshot.latestTrade() != null) {
            return snapshot.latestTrade().t();
        }
        if (snapshot.dailyBar() != null) {
            return snapshot.dailyBar().t();
        }
        if (snapshot.prevDailyBar() != null) {
            return snapshot.prevDailyBar().t();
        }
        return null;
    }
}
