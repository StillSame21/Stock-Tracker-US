package com.stocktracker.alert;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRepository extends JpaRepository<Alert, UUID> {
    List<Alert> findByStatus(String status);

    List<Alert> findByUserId(UUID userId);

    List<Alert> findByAssetIdAndStatus(UUID assetId, String status);
}
