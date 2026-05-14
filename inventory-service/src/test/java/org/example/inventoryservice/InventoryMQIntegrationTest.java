package org.example.inventoryservice;

import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.dto.StockRequest;
import org.example.inventoryservice.config.RedisKeys;
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
 *
 *  适配 onHandStock/soldStock schema:
 *    - LOCK/UNLOCK 只操作 Redis (不修改 DB)
 *    - CONFIRM 修改 DB (onHandStock--, soldStock++) + Redis (locked--)
 *    - 不再有 Inventory.lock() 方法；改用 Redis Lua 脚本模拟锁定状态
 * =====================================================================
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

        Set<String> stockKeys = stringRedisTemplate.keys("inventory:stock:*");
        if (stockKeys != null && !stockKeys.isEmpty()) {
            stringRedisTemplate.delete(stockKeys);
        }

        try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // Clean database
        inventoryOperationRepository.deleteAll();
        inventoryRepository.deleteAll();
        inventoryOperationRepository.flush();
        inventoryRepository.flush();
    }

    /** Simulate a "locked" state by setting Redis keys (since Inventory.lock() was removed) */
    private void simulateLockedState(Long productCode, int lockedQty, int onHandStock) {
        stringRedisTemplate.opsForValue().set(
                RedisKeys.availableStockKey(productCode), String.valueOf(onHandStock - lockedQty));
        stringRedisTemplate.opsForValue().set(
                RedisKeys.lockedStockKey(productCode), String.valueOf(lockedQty));
    }

    // ======================================================================
    //  场景 1 — UNLOCK 乐观锁冲突 → @Retryable 自动重试 → 最终成功
    //  库存演算 (new schema):
    //    初始: Inventory(product=2001, onHandStock=5, soldStock=0)
    //    Redis: avail=1, locked=4 (simulated locked state)
    //    unlock(2) x2 次 → Redis: avail=1+2+2=5, locked=4-2-2=0
    //    DB unchanged (UNLOCK only touches Redis)
    //  验证点:
    //    V1-1: 两条消息 → 最终都处理成功
    //    V1-2: 两条幂等记录均为 SUCCESS
    //    V1-3: Redis locked=0, avail=5; DB onHandStock=5
    // ======================================================================
    @Test
    @DisplayName("MQ场景1: UNLOCK乐观锁冲突→@Retryable自动重试→最终成功")
    void testUnlock_optimisticLock_retry_success() throws InterruptedException {
        // --- 准备 ---
        long productCode = 2001L;
        Inventory inventory = new Inventory(productCode, 5);
        inventoryRepository.saveAndFlush(inventory);
        // simulate: locked=4, avail=1
        simulateLockedState(productCode, 4, 5);

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
        waitUntil(() -> {
            String availStr = stringRedisTemplate.opsForValue()
                    .get(RedisKeys.availableStockKey(productCode));
            String lockedStr = stringRedisTemplate.opsForValue()
                    .get(RedisKeys.lockedStockKey(productCode));
            long avail = availStr == null ? 0 : Long.parseLong(availStr);
            long locked = lockedStr == null ? 0 : Long.parseLong(lockedStr);
            System.out.println("[DEBUG] UNLOCK场景1: avail=" + avail + ", locked=" + locked);
            return locked == 0 && avail == 5;
        }, 120000);

        // V1-3: Redis 状态验证
        String availStr = stringRedisTemplate.opsForValue()
                .get(RedisKeys.availableStockKey(productCode));
        String lockedStr = stringRedisTemplate.opsForValue()
                .get(RedisKeys.lockedStockKey(productCode));
        assertEquals("5", availStr, "V1-3: Redis avail=5");
        assertEquals("0", lockedStr, "V1-3: Redis locked=0");

        // DB onHandStock unchanged (UNLOCK only touches Redis)
        Inventory updated = inventoryRepository.findInventoryByProductCode(productCode)
                .orElseThrow(() -> new AssertionError("商品应存在"));
        assertEquals(5, updated.getOnHandStock(), "V1-3: DB onHandStock=5");

        // V1-2: 两条幂等记录均应为 SUCCESS
        Optional<InventoryOperation> opA = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderIdA, OperationType.UNLOCK);
        Optional<InventoryOperation> opB = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderIdB, OperationType.UNLOCK);
        assertTrue(opA.isPresent(), "V1-2: orderIdA的UNLOCK记录应存在");
        assertTrue(opB.isPresent(), "V1-2: orderIdB的UNLOCK记录应存在");
        assertEquals(OperationStatus.SUCCESS, opA.get().getOperationStatus(), "V1-2: orderIdA应为SUCCESS");
        assertEquals(OperationStatus.SUCCESS, opB.get().getOperationStatus(), "V1-2: orderIdB应为SUCCESS");
    }

    // ======================================================================
    //  场景 2 — CONFIRM 乐观锁冲突 → @Retryable 自动重试 → 最终成功
    //  验证点:
    //    V2-1: 两条消息 → 最终都处理成功
    //    V2-2: 库存最终状态: DB onHandStock=3, sold=7; Redis locked=0
    //    V2-3: 操作记录均为SUCCESS
    //  演算:
    //    初始: Inventory(onHandStock=10, soldStock=0)
    //    Redis: avail=3, locked=7 (simulated)
    //    CONFIRM(4) + CONFIRM(3) → DB: onHandStock=10-4-3=3, sold=7
    //                         Redis: locked=7-4-3=0, avail unchanged=3
    // ======================================================================
    @Test
    @DisplayName("MQ场景2: CONFIRM乐观锁冲突→@Retryable自动重试→最终成功")
    void testConfirm_optimisticLock_retry_success() throws InterruptedException {
        // --- 准备 ---
        long productCode = 2002L;
        Inventory inventory = new Inventory(productCode, 10);
        inventoryRepository.saveAndFlush(inventory);
        simulateLockedState(productCode, 7, 10);

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
            String lockedStrLocal = stringRedisTemplate.opsForValue()
                    .get(RedisKeys.lockedStockKey(productCode));
            long locked = lockedStrLocal == null ? 0 : Long.parseLong(lockedStrLocal);
            Inventory inv = inventoryRepository.findInventoryByProductCode(productCode)
                    .orElseThrow(() -> new RuntimeException("库存不存在"));
            System.out.println("[DEBUG] CONFIRM场景2: sold=" + inv.getSoldStock()
                    + ", onHand=" + inv.getOnHandStock() + ", redisLocked=" + locked);
            return inv.getSoldStock() == 7 && locked == 0;
        }, 120000);

        // V2-2: 验证库存
        Inventory updated = inventoryRepository.findInventoryByProductCode(productCode)
                .orElseThrow(() -> new AssertionError("商品应存在"));
        assertEquals(3, updated.getOnHandStock(), "V2-2: onHandStock=10-4-3=3");
        assertEquals(7, updated.getSoldStock(), "V2-2: sold=7");

        // Redis locked should be 0
        String lockedStr = stringRedisTemplate.opsForValue()
                .get(RedisKeys.lockedStockKey(productCode));
        assertEquals("0", lockedStr, "V2-2: Redis locked=0");

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
    //    V3-3: Redis avail=5, locked=0; DB onHandStock=5
    // ======================================================================
    @Test
    @DisplayName("MQ场景3: UNLOCK OperationProcessingException→重试等待→成功")
    void testUnlock_operationProcessing_retry_success() throws InterruptedException {
        long productCode = 2003L;
        Inventory inventory = new Inventory(productCode, 5);
        inventoryRepository.saveAndFlush(inventory);
        simulateLockedState(productCode, 3, 5); // locked=3, avail=2

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
            String availStr = stringRedisTemplate.opsForValue()
                    .get(RedisKeys.availableStockKey(productCode));
            String lockedStr = stringRedisTemplate.opsForValue()
                    .get(RedisKeys.lockedStockKey(productCode));
            long avail = availStr == null ? 0 : Long.parseLong(availStr);
            long locked = lockedStr == null ? 0 : Long.parseLong(lockedStr);
            System.out.println("[DEBUG] UNLOCK场景3: avail=" + avail + ", locked=" + locked);
            return avail == 5 && locked == 0;
        }, 60000);

        String availStr = stringRedisTemplate.opsForValue()
                .get(RedisKeys.availableStockKey(productCode));
        String lockedStr = stringRedisTemplate.opsForValue()
                .get(RedisKeys.lockedStockKey(productCode));
        assertEquals("5", availStr, "V3-3: Redis avail=5");
        assertEquals("0", lockedStr, "V3-3: Redis locked=0");

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
        inventoryRepository.saveAndFlush(inventory);
        simulateLockedState(productCode, 5, 10); // locked=5, avail=5

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
        assertEquals(5, updated.getOnHandStock(), "V4: onHandStock=5");
        assertEquals(5, updated.getSoldStock(), "V4: sold=5");
        // locked state is tracked in Redis, not in DB Inventory entity

        List<InventoryOperation> ops = inventoryOperationRepository.findAll();
        assertEquals(1, ops.size(), "V4: 幂等记录只有一条");
    }

    // ======================================================================
    //  场景 5 — UNLOCK 非法参数 → 不重试,快速失败
    // ======================================================================
    @Test
    @DisplayName("MQ场景5: UNLOCK业务异常(参数错)→不重试,快速失败+FAILED记录")
    void testUnlock_illegalArgument_noRetry_failed() throws InterruptedException {
        long productCode = 2005L;
        Inventory inventory = new Inventory(productCode, 5);
        inventoryRepository.saveAndFlush(inventory);
        simulateLockedState(productCode, 2, 5); // locked=2, avail=3

        long orderId = 5001L;
        // 尝试解锁 5 > locked=2, will fail
        InventoryBatchRequest request = buildBatchRequest(orderId, productCode, 5);

        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_UNLOCK_EXCHANGE,
                RabbitMQConfig.INVENTORY_UNLOCK_ROUTING_KEY,
                request
        );

        Thread.sleep(3000);

        // Redis unchanged (avail=3, locked=2)
        String availStr = stringRedisTemplate.opsForValue()
                .get(RedisKeys.availableStockKey(productCode));
        String lockedStr = stringRedisTemplate.opsForValue()
                .get(RedisKeys.lockedStockKey(productCode));
        assertEquals("3", availStr, "V5: Redis avail=3 unchanged");
        assertEquals("2", lockedStr, "V5: Redis locked=2 unchanged");

        Optional<InventoryOperation> op = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderId, OperationType.UNLOCK);
        assertTrue(op.isPresent(), "V5: 幂等记录应存在");
        assertEquals(OperationStatus.FAILED, op.get().getOperationStatus(), "V5: 状态应为FAILED");
    }

    // ======================================================================
    //  场景 6 — CONFIRM 幂等重复消息 → 仅执行一次
    // ======================================================================
    @Test
    @DisplayName("MQ场景6: CONFIRM重复消息→幂等控制仅执行一次")
    void testConfirmIdempotency_idempotentOnce() throws InterruptedException {
        long productCode = 1003L;
        Inventory inventory = new Inventory(productCode, 10);
        inventoryRepository.saveAndFlush(inventory);
        simulateLockedState(productCode, 4, 10); // locked=4, avail=6

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
        assertEquals(6, updated.getOnHandStock(), "V6: onHandStock=6 (10-4)");
        assertEquals(4, updated.getSoldStock(), "V6: sold=4");
        // locked state tracked in Redis, not in DB
        String redisLockedV6 = stringRedisTemplate.opsForValue()
                .get(RedisKeys.lockedStockKey(productCode));
        assertEquals("0", redisLockedV6, "V6: Redis locked=0");
        assertEquals(1, inventoryOperationRepository.findAll().size(), "V6: 仅1条幂等记录");
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