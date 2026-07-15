package com.stocktracker.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stocktracker.symbol.Symbol;
import com.stocktracker.symbol.SymbolRepository;

/** Local typeahead search — zero API calls, queries the synced {@code symbols} table only. */
@RestController
public class SymbolSearchController {

    private final SymbolRepository repository;

    public SymbolSearchController(SymbolRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/symbols/search")
    public List<Symbol> search(@RequestParam String q) {
        return repository.searchByPrefix(q);
    }
}
