package com.stocktracker.alert;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stocktracker.symbol.Symbol;
import com.stocktracker.symbol.SymbolRepository;
import com.stocktracker.symbol.SymbolValidator;
import com.stocktracker.symbol.UnsupportedSymbolException;

@Service
public class AlertService {

    private static final int MIN_COOLDOWN_SECONDS = 60;

    private final AlertRepository alertRepository;
    private final SymbolRepository symbolRepository;
    private final SymbolValidator symbolValidator;
    private final AlertIndex alertIndex;

    public AlertService(AlertRepository alertRepository, SymbolRepository symbolRepository,
                         SymbolValidator symbolValidator, AlertIndex alertIndex) {
        this.alertRepository = alertRepository;
        this.symbolRepository = symbolRepository;
        this.symbolValidator = symbolValidator;
        this.alertIndex = alertIndex;
    }

    @Transactional
    public Alert create(UUID userId, String symbolCode, Condition condition, BigDecimal threshold,
                         int cooldownSeconds, boolean extendedHours) {
        symbolValidator.validateAll(Set.of(symbolCode));
        if (threshold == null || threshold.signum() <= 0) {
            throw new InvalidAlertException("threshold must be > 0");
        }
        if (cooldownSeconds < MIN_COOLDOWN_SECONDS) {
            throw new InvalidAlertException("cooldown must be >= " + MIN_COOLDOWN_SECONDS + "s");
        }
        Symbol symbol = symbolRepository.findBySymbolIgnoreCase(symbolCode)
                .orElseThrow(() -> new UnsupportedSymbolException(Set.of(symbolCode)));

        Alert saved = alertRepository.save(
                new Alert(userId, symbol.getAssetId(), condition, threshold, cooldownSeconds, extendedHours));
        alertIndex.reload();
        return saved;
    }

    public List<Alert> findByUser(UUID userId) {
        return alertRepository.findByUserId(userId);
    }

    public Alert get(UUID alertId) {
        return alertRepository.findById(alertId)
                .orElseThrow(() -> new NoSuchElementException("No alert " + alertId));
    }

    @Transactional
    public void delete(UUID alertId) {
        alertRepository.deleteById(alertId);
        alertIndex.reload();
    }

    @Transactional
    public Alert setStatus(UUID alertId, String status) {
        Alert alert = get(alertId);
        alert.setStatus(status);
        Alert saved = alertRepository.save(alert);
        alertIndex.reload();
        return saved;
    }
}
