package org.example.inventoryservice.repository;

import org.example.inventoryservice.domain.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findInventoryByProductCode(Long productCode);
    List<Inventory> findInventoriesByProductCodeIn(List<Long> productCodes);
}
