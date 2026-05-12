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
import org.example.inventoryservice.service.InventoryIdempotencyExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * =====================================================================
 *  MQ 集成测试
 *  目标: 验证 InventoryEventListener 上的 @RabbitListener + @Retryable
 *        在真实 RabbitMQ 环境下的完整行为,包括乐观锁冲突自动重试。
 * =====================================================================
 *
 *  关键设计说明:
 *  ───────────────────────────────────────────────────────────────────
 *  乐观锁冲突场景（场景1、2）依赖于两个消息被不同的消费者线程并发处理。
 *  默认单消费者模式下消息串行处理，永远不会产生 OptimisticLockingFailureException。
 *  因此测试通过 spring.rabbitmq.listener.simple.concurrency=5 启用并发消费者。
 *
 *  ═══════════════════════════════════════════════════════════════════
 *  测试场景:
 *  ───────────────────────────────────────────────────────────────────
 *  场景 1: UNLOCK 乐观锁冲突 → @Retryable 自动重试 → 最终成功
 *  场景 2: CONFIRM 乐观锁冲突 → @Retryable 自动重试 → 最终成功
 *  场景 3: UNLOCK OperationProcessingException → @Retryable 重试等待 → 最终成功
 *  场景 4: CONFIRM OperationProcessingException → @Retryable 重试等待 → 最终成功
 *  场景 5: UNLOCK 失败(非法参数)→ 不重试,快速失败(IllegalArg not in @Retryable)
 *  场景 6: CONFIRM 幂等重复消息 → 仅执行一次
 *  ═══════════════════════════════════════════════════════════════════
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.concurrency=5",
        "spring.rabbitmq.listener.simple.max-concurrency=10",
        "spring.rabbitmq.listener.simple.prefetch=1"
})
public class InventoryMQIntegrationTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryOperationRepository inventoryOperationRepository;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private RabbitTemplate testRabbitTemplate;

    @BeforeEach
    void setup() {
        testRabbitTemplate = rabbitTemplate;

        // Purge queues
        testRabbitTemplate.execute(channel -> {
            channel.queuePurge(RabbitMQConfig.INVENTORY_UNLOCK_QUEUE);
            channel.queuePurge(RabbitMQConfig.INVENTORY_CONFIRM_QUEUE);
            return null;
        });

        // Clean Redis
        Set<String> idempotencyKeys = stringRedisTemplate.keys(InventoryIdempotencyExecutor.IDEM_PREFIX + "*");
        if (idempotencyKeys != null && !idempotencyKeys.isEmpty()) {
            stringRedisTemplate.delete(idempotencyKeys);
        }

        try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // Clean database
        inventoryOperationRepository.deleteAll();
        inventoryRepository.deleteAll();
        inventoryOperationRepository.flush();
        inventoryRepository.flush();
    }

    // ======================================================================
    //  场景 1 — UNLOCK 乐观锁冲突 → @Retryable 自动重试 → 最终成功
    //  验证点:
    //    V1-1: 两条消息(不同orderId,同一商品) → 最终都处理成功
    //    V1-2: 两条幂等记录均为 SUCCESS(一个直接成功,一个重试后重新INSERT)
    //    V1-3: 库存最终状态正确(2次UNLOCK各2件 → locked=0, available=5)
    //  库存演算:
    //    初始: Inventory(product=2001, available=5, locked=0)
    //    lock(4) → available=1, locked=4
    //    unlock(2) x2 次 → available=1+2+2=5, locked=4-2-2=0
    //  说明:
    //    两个消息被并发消费者同时处理
    //    一个成功,另一个触发 OptimisticLockingFailureException
    //    → deleteOperation + 删 Redis key
    //    → @Retryable 捕获 → 等待 2s → 重试 → 重新INSERT+执行业务成功
    // ======================================================================
    @Test
    @DisplayName("MQ场景1: UNLOCK乐观锁冲突→@Retryable自动重试→最终成功")
    void testUnlock_optimisticLock_retry_success() throws InterruptedException {
        // --- 准备 ---
        long productCode = 2001L;
        Inventory inventory = new Inventory(productCode, 5);
        inventory.lock(4); // available=1, locked=4
        inventoryRepository.saveAndFlush(inventory);

        long orderIdA = 1001L;
        long orderIdB = 1002L;
        int qty = 2;

        InventoryBatchRequest msgA = buildBatchRequest(orderIdA, productCode, qty);
        InventoryBatchRequest msgB = buildBatchRequest(orderIdB, productCode, qty);

        // --- 执行: 发送两条消息(并发消费者会同时处理) ---
        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_UNLOCK_EXCHANGE,
                RabbitMQConfig.INVENTORY_UNLOCK_ROUTING_KEY,
                msgA
        );
        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_UNLOCK_EXCHANGE,
                RabbitMQConfig.INVENTORY_UNLOCK_ROUTING_KEY,
                msgB
        );

        // --- 等待: 两个消息都应消费完成 ---
        // @Retryable(maxAttempts=5, delay=2000, multiplier=2) → 最大等待~30s
        // 库存: 两次 unlock(2) → available=1+2+2=5, locked=4-2-2=0
        waitUntil(() -> {
            Inventory inv = inventoryRepository.findInventoryByProductCode(productCode)
                    .orElseThrow(() -> new RuntimeException("库存不存在"));
            System.out.println("[DEBUG] UNLOCK场景1: locked=" + inv.getLockedStock()
                    + ", available=" + inv.getAvailableStock());
            return inv.getLockedStock() == 0 && inv.getAvailableStock() == 5;
        }, 120000);

        // V1-3: 库存最终状态
        Inventory updated = inventoryRepository.findInventoryByProductCode(productCode)
                .orElseThrow(() -> new AssertionError("商品应存在"));
        assertEquals(5, updated.getAvailableStock(),
                "V1-3: 初始5件,两次unlock(2)各解锁2件 → available=5(不是9)");
        assertEquals(0, updated.getLockedStock(),
                "V1-3: locked应为0");

        // V1-2: 两条幂等记录均应为 SUCCESS
        Optional<InventoryOperation> opA = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderIdA, OperationType.UNLOCK);
        Optional<InventoryOperation> opB = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderIdB, OperationType.UNLOCK);
        assertTrue(opA.isPresent(), "V1-2: orderIdA的UNLOCK记录应存在");
        assertTrue(opB.isPresent(), "V1-2: orderIdB的UNLOCK记录应存在");
        assertEquals(OperationStatus.SUCCESS, opA.get().getOperationStatus(),
                "V1-2: orderIdA记录应为SUCCESS");
        assertEquals(OperationStatus.SUCCESS, opB.get().getOperationStatus(),
                "V1-2: orderIdB记录应为SUCCESS");
    }

    // ======================================================================
    //  场景 2 — CONFIRM 乐观锁冲突 → @Retryable 自动重试 → 最终成功
    //  验证点:
    //    V2-1: 两条消息(不同orderId,同一商品) → 最终都处理成功
    //    V2-2: 库存最终状态正确(2次CONFIRM → locked=0, sold=4+3=7)
    //    V2-3: 操作记录均为SUCCESS
    // ======================================================================
    @Test
    @DisplayName("MQ场景2: CONFIRM乐观锁冲突→@Retryable自动重试→最终成功")
    void testConfirm_optimisticLock_retry_success() throws InterruptedException {
        // --- 准备 ---
        long productCode = 2002L;
        Inventory inventory = new Inventory(productCode, 10);
        inventory.lock(7); // locked=7, available=3
        inventoryRepository.saveAndFlush(inventory);

        long orderIdA = 2001L;
        long orderIdB = 2002L;

        InventoryBatchRequest msgA = buildBatchRequest(orderIdA, productCode, 4);
        InventoryBatchRequest msgB = buildBatchRequest(orderIdB, productCode, 3);

        // --- 执行: 发送两条消息(并发消费者会同时处理) ---
        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_CONFIRM_EXCHANGE,
                RabbitMQConfig.INVENTORY_CONFIRM_ROUTING_KEY,
                msgA
        );
        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_CONFIRM_EXCHANGE,
                RabbitMQConfig.INVENTORY_CONFIRM_ROUTING_KEY,
                msgB
        );

        // --- 等待 ---
        waitUntil(() -> {
            Inventory inv = inventoryRepository.findInventoryByProductCode(productCode)
                    .orElseThrow(() -> new RuntimeException("库存不存在"));
            System.out.println("[DEBUG] CONFIRM场景2: locked=" + inv.getLockedStock()
                    + ", sold=" + inv.getSoldStock());
            return inv.getLockedStock() == 0 && inv.getSoldStock() == 7;
        }, 120000);

        // V2-2: 验证库存
        Inventory updated = inventoryRepository.findInventoryByProductCode(productCode)
                .orElseThrow(() -> new AssertionError("商品应存在"));
        assertEquals(3, updated.getAvailableStock(), "V2-2: available=10-7=3");
        assertEquals(0, updated.getLockedStock(), "V2-2: locked=0");
        assertEquals(7, updated.getSoldStock(), "V2-2: sold=7");

        // V2-3: 操作记录
        Optional<InventoryOperation> opA = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderIdA, OperationType.CONFIRM);
        Optional<InventoryOperation> opB = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderIdB, OperationType.CONFIRM);
        assertTrue(opA.isPresent(), "V2-3: orderIdA的CONFIRM记录应存在");
        assertTrue(opB.isPresent(), "V2-3: orderIdB的CONFIRM记录应存在");
        assertEquals(OperationStatus.SUCCESS, opA.get().getOperationStatus(), "V2-3: orderIdA应为SUCCESS");
        assertEquals(OperationStatus.SUCCESS, opB.get().getOperationStatus(), "V2-3: orderIdB应为SUCCESS");
    }

    // ======================================================================
    //  场景 3 — UNLOCK OperationProcessingException → 重试后成功
    //  验证点:
    //    V3-1: 消息A先处理,消息B读到 PROCESSING → @Retryable 等待后重试
    //    V3-2: 重试时消息A已完成(SUCCESS) → 幂等返回成功
    //    V3-3: 库存只被解锁一次(available=5, locked=0)
    //  库存演算:
    //    初始: Inventory(5) → lock(3) → available=2, locked=3
    //    一次 unlock(3) → available=2+3=5, locked=3-3=0
    // ======================================================================
    @Test
    @DisplayName("MQ场景3: UNLOCK OperationProcessingException→重试等待→成功")
    void testUnlock_operationProcessing_retry_success() throws InterruptedException {
        long productCode = 2003L;
        Inventory inventory = new Inventory(productCode, 5);
        inventory.lock(3); // available=2, locked=3
        inventoryRepository.saveAndFlush(inventory);

        long orderId = 3001L;
        InventoryBatchRequest request = buildBatchRequest(orderId, productCode, 3);

        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_UNLOCK_EXCHANGE,
                RabbitMQConfig.INVENTORY_UNLOCK_ROUTING_KEY,
                request
        );
        Thread.sleep(100);
        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_UNLOCK_EXCHANGE,
                RabbitMQConfig.INVENTORY_UNLOCK_ROUTING_KEY,
                request
        );

        waitUntil(() -> {
            Inventory inv = inventoryRepository.findInventoryByProductCode(productCode)
                    .orElseThrow(() -> new RuntimeException("库存不存在"));
            System.out.println("[DEBUG] UNLOCK场景3: available=" + inv.getAvailableStock()
                    + ", locked=" + inv.getLockedStock());
            return inv.getAvailableStock() == 5 && inv.getLockedStock() == 0;
        }, 60000);

        Inventory updated = inventoryRepository.findInventoryByProductCode(productCode)
                .orElseThrow(() -> new AssertionError("商品应存在"));
        assertEquals(5, updated.getAvailableStock(), "V3-3: initial=5, unlock(3) → available=2+3=5");
        assertEquals(0, updated.getLockedStock(), "V3-3: locked=0");

        List<InventoryOperation> ops = inventoryOperationRepository.findAll();
        assertEquals(1, ops.size(), "V3-3: 幂等记录只有一条");
        assertEquals(OperationStatus.SUCCESS, ops.get(0).getOperationStatus(), "V3-3: 状态为SUCCESS");
    }

    // ======================================================================
    //  场景 4 — CONFIRM OperationProcessingException → 重试后成功
    // ======================================================================
    @Test
    @DisplayName("MQ场景4: CONFIRM OperationProcessingException→重试等待→成功")
    void testConfirm_operationProcessing_retry_success() throws InterruptedException {
        long productCode = 2004L;
        Inventory inventory = new Inventory(productCode, 10);
        inventory.lock(5);
        inventoryRepository.saveAndFlush(inventory);

        long orderId = 4001L;
        InventoryBatchRequest request = buildBatchRequest(orderId, productCode, 5);

        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_CONFIRM_EXCHANGE,
                RabbitMQConfig.INVENTORY_CONFIRM_ROUTING_KEY,
                request
        );
        Thread.sleep(100);
        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_CONFIRM_EXCHANGE,
                RabbitMQConfig.INVENTORY_CONFIRM_ROUTING_KEY,
                request
        );

        waitUntil(() -> {
            Inventory inv = inventoryRepository.findInventoryByProductCode(productCode)
                    .orElseThrow(() -> new RuntimeException("库存不存在"));
            System.out.println("[DEBUG] CONFIRM场景4: sold=" + inv.getSoldStock());
            return inv.getSoldStock() == 5;
        }, 60000);

        Inventory updated = inventoryRepository.findInventoryByProductCode(productCode)
                .orElseThrow(() -> new AssertionError("商品应存在"));
        assertEquals(5, updated.getAvailableStock(), "V4-2: available=5");
        assertEquals(0, updated.getLockedStock(), "V4-2: locked=0");
        assertEquals(5, updated.getSoldStock(), "V4-2: sold=5");

        List<InventoryOperation> ops = inventoryOperationRepository.findAll();
        assertEquals(1, ops.size(), "V4-2: 幂等记录只有一条");
    }

    // ======================================================================
    //  场景 5 — UNLOCK 非法参数 → 不重试,快速失败
    // ======================================================================
    @Test
    @DisplayName("MQ场景5: UNLOCK业务异常(参数错)→不重试,快速失败+FAILED记录")
    void testUnlock_illegalArgument_noRetry_failed() throws InterruptedException {
        long productCode = 2005L;
        Inventory inventory = new Inventory(productCode, 5);
        inventory.lock(2);
        inventoryRepository.saveAndFlush(inventory);

        long orderId = 5001L;
        InventoryBatchRequest request = buildBatchRequest(orderId, productCode, 5); // 解锁 5 > locked=2

        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_UNLOCK_EXCHANGE,
                RabbitMQConfig.INVENTORY_UNLOCK_ROUTING_KEY,
                request
        );

        // @Retryable 不捕获 IllegalArgumentException → 不会重试
        // 3s 足够
        Thread.sleep(3000);

        Inventory updated = inventoryRepository.findInventoryByProductCode(productCode)
                .orElseThrow(() -> new AssertionError("商品应存在"));
        assertEquals(3, updated.getAvailableStock(), "V5-4: 库存不变,available=3");
        assertEquals(2, updated.getLockedStock(), "V5-4: locked不变=2");

        Optional<InventoryOperation> op = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderId, OperationType.UNLOCK);
        assertTrue(op.isPresent(), "V5-3: 幂等记录应存在");
        assertEquals(OperationStatus.FAILED, op.get().getOperationStatus(), "V5-3: 状态应为FAILED");
    }

    // ======================================================================
    //  场景 6 — CONFIRM 幂等重复消息 → 仅执行一次(回归验证)
    // ======================================================================
    @Test
    @DisplayName("MQ场景6: CONFIRM重复消息→幂等控制仅执行一次")
    void testConfirmIdempotency_idempotentOnce() throws InterruptedException {
        long productCode = 1003L;
        Inventory inventory = new Inventory(productCode, 10);
        inventory.lock(4);
        inventoryRepository.saveAndFlush(inventory);

        long orderId = 3L;
        InventoryBatchRequest request = buildBatchRequest(orderId, productCode, 4);

        for (int i = 0; i < 25; i++) {
            testRabbitTemplate.convertAndSend(
                    RabbitMQConfig.INVENTORY_CONFIRM_EXCHANGE,
                    RabbitMQConfig.INVENTORY_CONFIRM_ROUTING_KEY,
                    request
            );
        }

        waitUntil(() -> {
            Inventory updated = inventoryRepository
                    .findInventoryByProductCode(productCode)
                    .orElseThrow();
            return updated.getSoldStock() == 4;
        }, 30000);

        Inventory updated = inventoryRepository
                .findInventoryByProductCode(productCode)
                .orElseThrow();
        assertEquals(6, updated.getAvailableStock());
        assertEquals(0, updated.getLockedStock());
        assertEquals(4, updated.getSoldStock());
        assertEquals(1, inventoryOperationRepository.findAll().size());
    }

    // ====================== Helpers ======================

    private InventoryBatchRequest buildBatchRequest(Long orderId, Long productCode, Integer quantity) {
        InventoryBatchRequest request = new InventoryBatchRequest();
        try {
            Field orderIdField = InventoryBatchRequest.class.getDeclaredField("orderId");
            orderIdField.setAccessible(true);
            orderIdField.set(request, orderId);

            Field stockListField = InventoryBatchRequest.class.getDeclaredField("stockRequestList");
            stockListField.setAccessible(true);
            stockListField.set(request, List.of(new StockRequest(productCode, quantity)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return request;
    }

    private void waitUntil(BooleanSupplier condition) throws InterruptedException {
        waitUntil(condition, 5000);
    }

    private void waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Condition not met within " + timeoutMs + "ms timeout");
    }
}