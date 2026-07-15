package com.stocktracker.watchlist;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stocktracker.quote.CachedQuoteService;
import com.stocktracker.quote.Quote;
import com.stocktracker.symbol.Symbol;
import com.stocktracker.symbol.SymbolRepository;
import com.stocktracker.symbol.SymbolValidator;
import com.stocktracker.symbol.UnsupportedSymbolException;

@Service
public class WatchlistService {

    private final WatchlistRepository watchlistRepository;
    private final SymbolRepository symbolRepository;
    private final SymbolValidator symbolValidator;
    private final CachedQuoteService cachedQuoteService;

    public WatchlistService(WatchlistRepository watchlistRepository, SymbolRepository symbolRepository,
                             SymbolValidator symbolValidator, CachedQuoteService cachedQuoteService) {
        this.watchlistRepository = watchlistRepository;
        this.symbolRepository = symbolRepository;
        this.symbolValidator = symbolValidator;
        this.cachedQuoteService = cachedQuoteService;
    }

    @Transactional
    public Watchlist create(UUID userId, String name) {
        return watchlistRepository.save(new Watchlist(userId, name));
    }

    public List<Watchlist> findByUser(UUID userId) {
        return watchlistRepository.findByUserId(userId);
    }

    public Watchlist get(UUID watchlistId) {
        return watchlistRepository.findById(watchlistId)
                .orElseThrow(() -> new NoSuchElementException("No watchlist " + watchlistId));
    }

    @Transactional
    public Watchlist addSymbol(UUID watchlistId, String symbolCode) {
        symbolValidator.validateAll(Set.of(symbolCode));
        Watchlist watchlist = get(watchlistId);
        Symbol symbol = symbolRepository.findBySymbolIgnoreCase(symbolCode)
                .orElseThrow(() -> new UnsupportedSymbolException(Set.of(symbolCode)));
        watchlist.addSymbol(symbol);
        return watchlistRepository.save(watchlist);
    }

    @Transactional
    public Watchlist removeSymbol(UUID watchlistId, String symbolCode) {
        Watchlist watchlist = get(watchlistId);
        watchlist.getSymbols().removeIf(s -> s.getSymbol().equalsIgnoreCase(symbolCode));
        return watchlistRepository.save(watchlist);
    }

    /** Joins the watchlist to the quote cache — one upstream call for the whole list, not one per symbol. */
    @Transactional(readOnly = true)
    public Map<String, Quote> quotesFor(UUID watchlistId) {
        Watchlist watchlist = get(watchlistId);
        Set<String> symbols = watchlist.getSymbols().stream().map(Symbol::getSymbol).collect(Collectors.toSet());
        return cachedQuoteService.getQuotes(symbols);
    }
}
