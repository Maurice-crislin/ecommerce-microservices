package org.example.inventoryservice;

import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.dto.StockRequest;
import org.example.inventoryservice.domain.Inventory;
import org.example.inventoryservice.domain.InventoryOperation;
import org.example.inventoryservice.domain.OperationStatus;
import org.example.inventoryservice.domain.OperationType;
import org.example.inventoryservice.messaging.RabbitMQConfig;
import org.example.inventoryservice.repository.InventoryOperationRepository;
import org.example.inventoryservice.repository.InventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class InventoryMQIntegrationTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryOperationRepository inventoryOperationRepository;

    private RabbitTemplate testRabbitTemplate;

    @BeforeEach
    void setup() {

        testRabbitTemplate = rabbitTemplate;
        // clean db
        inventoryOperationRepository.deleteAll();
        inventoryRepository.deleteAll();

        inventoryOperationRepository.flush();
        inventoryRepository.flush();
    }

    // ====================== CONFIRM ======================

    @Test
    public void testConfirmStockListener() throws InterruptedException {

        // 1️⃣ prepare inventory
        Inventory inventory = new Inventory(1001L, 10);

        // simulate locked stock
        inventory.lock(3);
        inventoryRepository.saveAndFlush(inventory);

        // 2️⃣ build mq message
        InventoryBatchRequest request = buildBatchRequest(1L, 1001L, 3);

        // 3️⃣ send mq
        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_CONFIRM_EXCHANGE,
                RabbitMQConfig.INVENTORY_CONFIRM_ROUTING_KEY,
                request
        );

        // 4️⃣ wait async
        waitUntil(() -> {
            Inventory updated = inventoryRepository
                    .findInventoryByProductCode(1001L)
                    .orElseThrow();
            return updated.getSoldStock() == 3;
        });

        // 5️⃣ verify inventory
        Inventory updated = inventoryRepository
                .findInventoryByProductCode(1001L)
                .orElseThrow();

        assertEquals(7, updated.getAvailableStock());
        assertEquals(0, updated.getLockedStock());
        assertEquals(3, updated.getSoldStock());

        // 6️⃣ verify idempotency record
        InventoryOperation op =
                inventoryOperationRepository
                        .findByOrderIdAndOperationType(1L, OperationType.CONFIRM)
                        .orElseThrow();

        assertEquals(OperationStatus.SUCCESS, op.getOperationStatus());
    }

    // ====================== UNLOCK ======================

    @Test
    public void testUnlockStockListener() throws InterruptedException {

        Inventory inventory = new Inventory(1002L, 5);
        inventory.lock(2);
        inventoryRepository.saveAndFlush(inventory);

        InventoryBatchRequest request = buildBatchRequest(2L, 1002L, 2);

        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_UNLOCK_EXCHANGE,
                RabbitMQConfig.INVENTORY_UNLOCK_ROUTING_KEY,
                request
        );

        waitUntil(() -> {
            boolean stockUpdated = inventoryRepository
                    .findInventoryByProductCode(1002L)
                    .map(inv -> inv.getLockedStock() == 0)
                    .orElse(false);

            boolean opInserted = inventoryOperationRepository
                    .findByOrderIdAndOperationType(2L, OperationType.UNLOCK)
                    .isPresent();

            return stockUpdated && opInserted;
        });

        Inventory updated = inventoryRepository
                .findInventoryByProductCode(1002L)
                .orElseThrow();

        assertEquals(5, updated.getAvailableStock());
        assertEquals(0, updated.getLockedStock());
        assertEquals(0, updated.getSoldStock());

        InventoryOperation op =
                inventoryOperationRepository
                        .findByOrderIdAndOperationType(2L, OperationType.UNLOCK)
                        .orElseThrow();

        assertEquals(OperationStatus.SUCCESS, op.getOperationStatus());
    }

    // ====================== IDEMPOTENCY ======================

    @Test
    public void testConfirmIdempotency() throws InterruptedException {

        Inventory inventory = new Inventory(1003L, 10);

        inventory.lock(4);
        inventoryRepository.saveAndFlush(inventory);

        InventoryBatchRequest request = buildBatchRequest(3L, 1003L, 4);

        // send duplicate messages
        int concurrence_count = 25;
        for (int i = 0; i< concurrence_count; i++ ){
            testRabbitTemplate.convertAndSend(
                    RabbitMQConfig.INVENTORY_CONFIRM_EXCHANGE,
                    RabbitMQConfig.INVENTORY_CONFIRM_ROUTING_KEY,
                    request
            );
        }

        waitUntil(() -> {
            Inventory updated = inventoryRepository
                    .findInventoryByProductCode(1003L)
                    .orElseThrow();
            return updated.getSoldStock() == 4;
        });

        Inventory updated = inventoryRepository
                .findInventoryByProductCode(1003L)
                .orElseThrow();

        assertEquals(6, updated.getAvailableStock());
        assertEquals(0, updated.getLockedStock());
        assertEquals(4, updated.getSoldStock());

        assertEquals(
                1,
                inventoryOperationRepository
                        .findAll()
                        .size()
        );
    }


    // ====================== UNLOCK IDEMPOTENCY ======================

    @Test
    public void testUnlockIdempotency() throws InterruptedException {

        // 1️⃣ 准备库存：总量 5，锁定 3
        Inventory inventory = new Inventory(1004L, 5);
        inventory.lock(3);
        inventoryRepository.saveAndFlush(inventory);

        // 2️⃣ 构建 MQ 请求
        InventoryBatchRequest request = buildBatchRequest(4L, 1004L, 3);


        // send duplicate messages
        int concurrence_count = 15;
        for (int i = 0; i< concurrence_count; i++ ){
            testRabbitTemplate.convertAndSend(
                    RabbitMQConfig.INVENTORY_UNLOCK_EXCHANGE,
                    RabbitMQConfig.INVENTORY_UNLOCK_ROUTING_KEY,
                    request
            );
        }

        // 4️⃣ 等待异步处理完成
        waitUntil(() -> {
            Inventory updated = inventoryRepository
                    .findInventoryByProductCode(1004L)
                    .orElseThrow();
            boolean stockUpdated = updated.getLockedStock() == 0 && updated.getAvailableStock() == 5;
            boolean opInserted = inventoryOperationRepository
                    .findByOrderIdAndOperationType(4L, OperationType.UNLOCK)
                    .isPresent();
            return stockUpdated && opInserted;
        });

        // 5️⃣ 验证库存状态
        Inventory updated = inventoryRepository
                .findInventoryByProductCode(1004L)
                .orElseThrow();
        assertEquals(5, updated.getAvailableStock());
        assertEquals(0, updated.getLockedStock());
        assertEquals(0, updated.getSoldStock());

        // 6️⃣ 验证幂等性记录只插入一次
        List<InventoryOperation> ops = inventoryOperationRepository.findAll();
        assertEquals(1, ops.size());

        InventoryOperation op = inventoryOperationRepository
                .findByOrderIdAndOperationType(4L, OperationType.UNLOCK)
                .orElseThrow();
        assertEquals(OperationStatus.SUCCESS, op.getOperationStatus());
    }

    // ====================== Helpers ======================

    private InventoryBatchRequest buildBatchRequest(
            Long orderId,
            Long productCode,
            Integer quantity
    ) {

        InventoryBatchRequest request = new InventoryBatchRequest();

        try {
            Field orderIdField = InventoryBatchRequest.class.getDeclaredField("orderId");
            orderIdField.setAccessible(true);
            orderIdField.set(request, orderId);

            Field stockListField = InventoryBatchRequest.class.getDeclaredField("stockRequestList");
            stockListField.setAccessible(true);
            stockListField.set(
                    request,
                    List.of(new StockRequest(productCode, quantity))
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return request;
    }

    private void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long timeout = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < timeout) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Condition not met within timeout");
    }
}
