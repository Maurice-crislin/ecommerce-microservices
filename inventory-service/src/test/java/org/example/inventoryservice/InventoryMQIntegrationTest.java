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

        // 1️⃣ Purge queues to avoid message leakage between tests
        testRabbitTemplate.execute(channel -> {
            channel.queuePurge(RabbitMQConfig.INVENTORY_UNLOCK_QUEUE);
            channel.queuePurge(RabbitMQConfig.INVENTORY_CONFIRM_QUEUE);
            return null;
        });

        // 2️⃣ Wait a bit for any in-flight messages from previous test to finish
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 3️⃣ Clean database
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

    /** ## 问题分析

     根据错误日志和代码分析，`testUnlockIdempotency()` 测试失败的根本原因是**幂等性机制在并发场景下的实现缺陷**。

     ### 错误现象
     1. **SQL Error 1062**: 唯一约束冲突 - `Duplicate entry '4-UNLOCK' for key 'inventory_operation.UKqcb0q1mdk8chvdcb5w07rywnn'`
     2. **OperationProcessingException**: "Inventory lock is still processing for order 4"
     3. **IllegalStateException**: "Previous operation failed for order 3"
     4. **断言失败**: Expected: 1, Actual: 2（插入了2条记录而不是预期的1条）

     ### 根本原因分析

     #### 1. 幂等性检查逻辑缺陷
     在 `InventoryIdempotencyExecutor.executeWithIdempotency()` 方法中：

     ```java
     @Transactional(propagation = Propagation.REQUIRES_NEW)
     public void executeWithIdempotency(Long orderId, OperationType operationType, Runnable batchLogic) {
     try {
     inventoryOperationService.getOrStartOperation(orderId, operationType);
     } catch (DataIntegrityViolationException e) {
     InventoryOperation inventoryOperation = inventoryOperationService.getOperationByOrderIdAndOperationType(orderId, operationType);

     if(inventoryOperation.getOperationStatus() == OperationStatus.FAILED){
     throw new IllegalStateException("Previous operation failed for order " + orderId);
     }

     if(inventoryOperation.getOperationStatus() == OperationStatus.SUCCESS){
     return; // 幂等返回
     }

     if (inventoryOperation.getOperationStatus() == OperationStatus.PROCESSING){
     throw new OperationProcessingException("Inventory lock is still processing for order " + orderId);
     }
     }

     try {
     batchLogic.run();
     inventoryOperationService.markSuccess(orderId, operationType);
     } catch (IllegalArgumentException e) {
     inventoryOperationService.markFailed(orderId, operationType);
     throw e;
     }
     }
     ```

      **问题**：当多个线程同时执行时：
     - 线程A成功插入 `InventoryOperation` 记录（状态为 `PROCESSING`）
     - 线程B、C、D等尝试插入时遇到唯一约束冲突
     - 它们捕获异常后查询记录状态，但此时线程A的事务可能还未提交
     - 线程B看到状态为 `PROCESSING`，抛出 `OperationProcessingException`
     - 线程A的业务逻辑执行完成后调用 `markSuccess()`，但异常处理可能导致事务回滚

     #### 2. 事务边界问题
     - `getOrStartOperation()` 使用 `REQUIRES_NEW` 传播级别，确保独立提交
     - 但 `batchLogic.run()` 和 `markSuccess()` 在同一个事务中
     - 如果 `batchLogic.run()` 抛出异常，整个事务回滚，包括已插入的 `InventoryOperation` 记录

     #### 3. 并发控制不足
     在高并发场景下（15个线程同时发送解锁消息）：
     - 多个线程可能同时通过唯一约束检查
     - 乐观锁机制未能有效防止重复插入
     - 异常处理逻辑没有考虑重试机制

     ### 解决方案建议

     1. **改进幂等性检查**：在捕获 `DataIntegrityViolationException` 后，应该重试检查记录状态，而不是立即抛出异常

     2. **增强事务隔离**：确保 `InventoryOperation` 记录的创建和状态更新是原子的

     3. **添加重试机制**：对于 `PROCESSING` 状态的记录，应该等待并重试，而不是直接失败

     4. **使用分布式锁**：在分布式环境下，考虑使用 Redis 或 Zookeeper 实现分布式锁

     这个测试暴露了在高并发场景下幂等性实现的不足，需要优化并发控制逻辑。*/
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
