package org.example.inventoryservice;

import org.example.inventoryservice.domain.Inventory;
import org.example.inventoryservice.repository.InventoryRepository;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class DatabaseTest {
    @Autowired
    private InventoryRepository inventoryRepository;
    @Test
    void testDatabaseConnection() {
        // add a stock record
        Inventory inventory = new Inventory(2001L, 50);
        inventoryRepository.save(inventory);

        // find
        Optional<Inventory> result = inventoryRepository.findInventoryByProductCode(2001L);
        assertTrue(result.isPresent());

        System.out.println("Inventory fetched: " + result.get().getProductCode() + " - " + result.get().getAvailableStock());

        // clear
        inventoryRepository.delete(inventory);
    }
}
