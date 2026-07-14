package com.stocktracker.symbol;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SymbolRepository extends JpaRepository<Symbol, UUID> {

    @Query("select s from Symbol s where s.tradable = true and upper(s.symbol) like upper(concat(:prefix, '%')) order by s.symbol")
    List<Symbol> searchByPrefix(@Param("prefix") String prefix);

    @Query("select s.symbol from Symbol s where s.tradable = true")
    List<String> findAllTradableSymbols();
}
