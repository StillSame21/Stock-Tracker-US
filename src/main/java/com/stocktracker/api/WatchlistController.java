package com.stocktracker.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stocktracker.quote.Quote;
import com.stocktracker.watchlist.Watchlist;
import com.stocktracker.watchlist.WatchlistService;

@RestController
public class WatchlistController {

    private final WatchlistService watchlistService;

    public WatchlistController(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    public record CreateWatchlistRequest(UUID userId, String name) {
    }

    public record AddSymbolRequest(String symbol) {
    }

    @PostMapping("/api/watchlists")
    public Watchlist create(@RequestBody CreateWatchlistRequest request) {
        return watchlistService.create(request.userId(), request.name());
    }

    @GetMapping("/api/watchlists")
    public List<Watchlist> listForUser(@RequestParam UUID userId) {
        return watchlistService.findByUser(userId);
    }

    @GetMapping("/api/watchlists/{id}")
    public Watchlist get(@PathVariable UUID id) {
        return watchlistService.get(id);
    }

    @PostMapping("/api/watchlists/{id}/items")
    public Watchlist addSymbol(@PathVariable UUID id, @RequestBody AddSymbolRequest request) {
        return watchlistService.addSymbol(id, request.symbol());
    }

    @DeleteMapping("/api/watchlists/{id}/items/{symbol}")
    public Watchlist removeSymbol(@PathVariable UUID id, @PathVariable String symbol) {
        return watchlistService.removeSymbol(id, symbol);
    }

    @GetMapping("/api/watchlists/{id}/quotes")
    public Map<String, Quote> quotes(@PathVariable UUID id) {
        return watchlistService.quotesFor(id);
    }
}
