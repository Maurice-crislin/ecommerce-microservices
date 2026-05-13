package org.example.inventoryservice.service;

import org.common.inventory.dto.StockRequest;

import java.util.List;

public interface InventoryDomainService {
    void batchLockStock(Long orderId, List<StockRequest> stockRequestList);
    void batchConfirmSale(Long orderId, List<StockRequest> stockRequestList);
    void batchUnlockStock(Long orderId, List<StockRequest> stockRequestList);
}
