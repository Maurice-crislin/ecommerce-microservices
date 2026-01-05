package org.example.inventoryservice.service;




import org.common.inventory.dto.StockRequest;

import java.util.List;

public interface InventoryDomainService {
    void batchLockStock(List<StockRequest> stockRequestList);
    void batchConfirmSale(List<StockRequest> stockRequestList);
    void batchUnlockStock(List<StockRequest> stockRequestList);
}
