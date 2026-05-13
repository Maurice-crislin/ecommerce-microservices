package org.example.inventoryservice.repository;

import org.example.inventoryservice.domain.InventoryLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryLogRepository extends JpaRepository<InventoryLog, Long> {

}
