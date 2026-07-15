package com.stocktracker.watchlist;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

import com.stocktracker.symbol.Symbol;

@Entity
@Table(name = "watchlists")
public class Watchlist {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String name;

    @ManyToMany
    @JoinTable(
            name = "watchlist_items",
            joinColumns = @JoinColumn(name = "watchlist_id"),
            inverseJoinColumns = @JoinColumn(name = "asset_id"))
    private Set<Symbol> symbols = new LinkedHashSet<>();

    protected Watchlist() {
        // JPA
    }

    public Watchlist(UUID userId, String name) {
        this.userId = userId;
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public Set<Symbol> getSymbols() {
        return symbols;
    }

    public void addSymbol(Symbol symbol) {
        symbols.add(symbol);
    }

    public void removeSymbol(Symbol symbol) {
        symbols.remove(symbol);
    }
}
