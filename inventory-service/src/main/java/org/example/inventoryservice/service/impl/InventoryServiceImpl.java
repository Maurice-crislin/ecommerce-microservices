package org.example.inventoryservice.service.impl;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.example.inventoryservice.domain.Inventory;
import org.example.inventoryservice.repository.InventoryRepository;
import org.example.inventoryservice.service.InventoryService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Transactional
public class InventoryServiceImpl implements InventoryService {

    private final InventoryRepository inventoryRepository;
    @Override
    public void deductStockDirectly(Long productCode, Integer quantity) {
        Inventory inventory = inventoryRepository.findInventoryByProductCode(productCode)
                .orElseThrow(()-> new IllegalArgumentException("Product not found"));

        inventory.deductStock(quantity);

        // no need to save()
        // because jpa can auto flush when transition was submitted

    }

    @Override
    public void addStock(Long productCode, Integer quantity) {
        Inventory inventory = inventoryRepository.findInventoryByProductCode(productCode)
                .orElseThrow(()-> new IllegalArgumentException("Product not found"));
        inventory.increaseStock(quantity);

    }

    @Override
    public void lockStock(Long productCode, Integer quantity) {
        Inventory inventory = inventoryRepository.findInventoryByProductCode(productCode)
                .orElseThrow(()-> new IllegalArgumentException("Product not found"));
        inventory.lock(quantity);
    }
    @Override
    public void unlockStock(Long productCode, Integer quantity) {
        Inventory inventory = inventoryRepository.findInventoryByProductCode(productCode)
                .orElseThrow(()-> new IllegalArgumentException("Product not found"));

        inventory.unlock(quantity);
    }

    @Override
    public void confirmSale(Long productCode, Integer quantity) {
        Inventory inventory = inventoryRepository.findInventoryByProductCode(productCode)
                .orElseThrow(()-> new IllegalArgumentException("Product not found"));

        inventory.confirmSale(quantity);
    }
}
