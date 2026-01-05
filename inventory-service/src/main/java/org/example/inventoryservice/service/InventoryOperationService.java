package org.example.inventoryservice.service;

import org.example.inventoryservice.domain.InventoryOperation;
import org.example.inventoryservice.domain.OperationType;

public interface InventoryOperationService {
    InventoryOperation getOrStartOperation(Long orderId, OperationType operationType);
    void markSuccess(Long orderId, OperationType operationType);
    void markFailed(Long orderId, OperationType operationType);
    InventoryOperation getOperationByOrderIdAndOperationType(Long orderId, OperationType operationType);
}
