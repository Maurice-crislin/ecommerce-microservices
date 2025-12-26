package org.example.inventoryservice.service.impl;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.example.inventoryservice.domain.Inventory;
import org.example.inventoryservice.repository.InventoryRepository;
import org.example.inventoryservice.service.InventoryService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private final InventoryRepository inventoryRepository;
    @Transactional
    @Override
    public void deductStock(Long productCode, Integer quantity) {
        Inventory inventory = inventoryRepository.findInventoryByProductCode(productCode)
                .orElseThrow(()-> new IllegalArgumentException("Product not found"));

        inventory.deductStock(quantity);

        // no need to save()
        // because jpa can auto flush when transition was submitted
    }
}
