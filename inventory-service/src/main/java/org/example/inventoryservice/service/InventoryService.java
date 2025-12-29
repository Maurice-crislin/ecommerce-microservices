package org.example.inventoryservice.service;

public interface InventoryService {
    void deductStockDirectly(Long productCode, Integer quantity);
    void addStock(Long productCode, Integer quantity);
    void lockStock(Long productCode, Integer quantity);
    void unlockStock(Long productCode, Integer quantity);
    void confirmSale(Long productCode, Integer quantity);

}
