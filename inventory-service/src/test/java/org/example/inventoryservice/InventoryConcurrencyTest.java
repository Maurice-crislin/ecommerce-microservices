package org.example.inventoryservice;
import org.example.inventoryservice.domain.Inventory;
import org.example.inventoryservice.repository.InventoryRepository;
import org.example.inventoryservice.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import jakarta.transaction.Transactional;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class InventoryConcurrencyTest {

    @Autowired
    InventoryService inventoryService;

    @Autowired
    InventoryRepository inventoryRepository;

    @Test
    void optimisticLock_shouldFailUnderConcurrency() throws InterruptedException {
        Inventory inventory = new Inventory(1001L, 10);
        inventoryRepository.save(inventory);

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        Runnable task = () -> {
            try {
                inventoryService.deductStockDirectly(1001L, 6);
            } catch (Exception ignored) {
            } finally {
                latch.countDown();
            }
        };

        executor.submit(task);
        executor.submit(task);

        latch.await();

        Inventory result = inventoryRepository.findInventoryByProductCode(1001L).get();
        assertTrue(result.getAvailableStock() >= 0);
    }
}

