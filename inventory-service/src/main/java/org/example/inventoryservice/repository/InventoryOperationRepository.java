package org.example.inventoryservice.repository;

import org.example.inventoryservice.domain.InventoryOperation;
import org.example.inventoryservice.domain.OperationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;


public interface InventoryOperationRepository extends JpaRepository<InventoryOperation, Long> {

    boolean existsByOrderIdAndOperationType(Long orderId, OperationType operationType);
    Optional<InventoryOperation> findByOrderIdAndOperationType(Long orderId, OperationType operationType);
}

