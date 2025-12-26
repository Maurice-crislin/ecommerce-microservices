package org.example.inventoryservice.service;

public interface InventoryService {
    void deductStock(Long productCode, Integer quantity);
}
