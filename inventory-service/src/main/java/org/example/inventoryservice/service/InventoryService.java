package org.example.inventoryservice.service;

import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.dto.StockRequest;

import java.util.List;

public interface InventoryService {
    void deductStockDirectly(Long productCode, Integer quantity);
    void addStock(Long productCode, Integer quantity);
    List<Long> batchCheckStock(List<StockRequest> stockRequestList);
    void batchLockStockWithIdempotency(InventoryBatchRequest inventoryBatchEvent);
    void batchConfirmSaleWithIdempotency(InventoryBatchRequest inventoryBatchEvent);
    void batchUnlockStockWithIdempotency(InventoryBatchRequest inventoryBatchEvent);
}
