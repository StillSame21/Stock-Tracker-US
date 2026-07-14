package com.stocktracker.api;

import java.util.List;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stocktracker.gateway.MarketDataProvider;
import com.stocktracker.quote.Quote;
import com.stocktracker.symbol.SymbolValidator;

@RestController
public class QuoteController {

    private final MarketDataProvider marketDataProvider;
    private final SymbolValidator symbolValidator;

    public QuoteController(MarketDataProvider marketDataProvider, SymbolValidator symbolValidator) {
        this.marketDataProvider = marketDataProvider;
        this.symbolValidator = symbolValidator;
    }

    @GetMapping("/api/quotes")
    public List<Quote> quotes(@RequestParam Set<String> symbols) {
        symbolValidator.validateAll(symbols);
        return marketDataProvider.getQuotes(symbols).values().stream().toList();
    }
}
