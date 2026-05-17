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
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
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
 * MQ 集成测试
 *
 * 适配 onHandStock/soldStock schema:
 *   - UNLOCK 只操作 Redis Lua + DB InventoryLog (不修改 Inventory 实体 → 无 @Version 冲突)
 *   - CONFIRM 操作 DB (onHandStock--, soldStock++) + Redis (locked--) → 有 @Version 冲突
 *
 * 因此:
 *   - 测试"乐观锁冲突 @Retryable"的场景 → 必须用 CONFIRM (场景2)
 *   - 测试"OperationProcessingException @Retryable"的场景 → 可用 UNLOCK 或 CONFIRM (场景3,4)
 *   - UNLOCK 无法触发 @Version 冲突: 两个不同 orderId 的 UNLOCK 都会成功
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

    @Autowired
    private RabbitListenerEndpointRegistry rabbitListenerEndpointRegistry;

    private RabbitTemplate testRabbitTemplate;

    @BeforeEach
    void setup() {
        testRabbitTemplate = rabbitTemplate;

        // ======== Stop all RabbitMQ listeners to prevent in-flight messages from previous tests ========
        rabbitListenerEndpointRegistry.getListenerContainers().forEach(container -> {
            container.stop();
        });
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // ======== Purge queues ========
        testRabbitTemplate.execute(channel -> {
            channel.queuePurge(RabbitMQConfig.INVENTORY_UNLOCK_QUEUE);
            channel.queuePurge(RabbitMQConfig.INVENTORY_CONFIRM_QUEUE);
            return null;
        });

        // ======== Clean Redis ========
        Set<String> idempotencyKeys = stringRedisTemplate.keys(InventoryIdempotencyExecutor.IDEM_PREFIX + "*");
        if (idempotencyKeys != null && !idempotencyKeys.isEmpty()) {
            stringRedisTemplate.delete(idempotencyKeys);
        }
        Set<String> stockKeys = stringRedisTemplate.keys("inventory:stock:*");
        if (stockKeys != null && !stockKeys.isEmpty()) {
            stringRedisTemplate.delete(stockKeys);
        }

        // ======== Clean database ========
        inventoryOperationRepository.deleteAll();
        inventoryRepository.deleteAll();
        inventoryOperationRepository.flush();
        inventoryRepository.flush();

        // ======== Restart all RabbitMQ listeners ========
        rabbitListenerEndpointRegistry.getListenerContainers().forEach(container -> {
            container.start();
        });
        try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void simulateLockedState(Long productCode, int lockedQty, int onHandStock) {
        stringRedisTemplate.opsForValue().set(
                RedisKeys.availableStockKey(productCode), String.valueOf(onHandStock - lockedQty));
        stringRedisTemplate.opsForValue().set(
                RedisKeys.lockedStockKey(productCode), String.valueOf(lockedQty));
    }

    // ======================================================================
    //  场景 1 — UNLOCK 两消息同一 orderId (OPERATION_PROCESSING retry)
    //  验证同 orderId 并发时，@Retryable 能够自动重试并幂等返回
    // ======================================================================
    @Test
    @DisplayName("MQ场景1: UNLOCK同orderId→OperationProcessing→@Retryable→成功")
    void testUnlock_operationProcessing_retry_success() throws InterruptedException {
        long productCode = 2001L;
        Inventory inventory = new Inventory(productCode, 5);
        inventoryRepository.saveAndFlush(inventory);
        simulateLockedState(productCode, 4, 5); // locked=4, avail=1

        long orderId = 1001L;
        int qty = 2;
        InventoryBatchRequest msg = buildBatchRequest(orderId, productCode, qty);

        // 发送两条完全相同 orderId 的消息 (并发消费者处理)
        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_UNLOCK_EXCHANGE,
                RabbitMQConfig.INVENTORY_UNLOCK_ROUTING_KEY,
                msg
        );
        Thread.sleep(50);
        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_UNLOCK_EXCHANGE,
                RabbitMQConfig.INVENTORY_UNLOCK_ROUTING_KEY,
                msg
        );

        // 等待: 两条消息应只执行业务一次
        waitUntil(() -> {
            String lockedStr = stringRedisTemplate.opsForValue()
                    .get(RedisKeys.lockedStockKey(productCode));
            long locked = lockedStr == null ? 0 : Long.parseLong(lockedStr);
            return locked == 2; // 只解锁一次: 4-2=2
        }, 30000);

        assertEquals("3", stringRedisTemplate.opsForValue()
                .get(RedisKeys.availableStockKey(productCode)), "avail=3 (1+2)");
        assertEquals("2", stringRedisTemplate.opsForValue()
                .get(RedisKeys.lockedStockKey(productCode)), "locked=2 (4-2)");

        // 幂等记录只一条 (SUCCESS)
        List<InventoryOperation> ops = inventoryOperationRepository.findAll();
        assertEquals(1, ops.size(), "仅一条幂等记录");
        assertEquals(OperationStatus.SUCCESS, ops.get(0).getOperationStatus());
    }

    // ======================================================================
    //  场景 2 — CONFIRM 乐观锁冲突 → @Retryable 自动重试 → 最终成功
    //  两个不同 orderId CONFIRM 同一商品 → @Version 冲突
    // ======================================================================
    @Test
    @DisplayName("MQ场景2: CONFIRM乐观锁冲突→@Retryable自动重试→最终成功")
    void testConfirm_optimisticLock_retry_success() throws InterruptedException {
        long productCode = 2002L;
        Inventory inventory = new Inventory(productCode, 10);
        inventoryRepository.saveAndFlush(inventory);
        simulateLockedState(productCode, 7, 10); // locked=7, avail=3

        long orderIdA = 2001L;
        long orderIdB = 2002L;

        InventoryBatchRequest msgA = buildBatchRequest(orderIdA, productCode, 4);
        InventoryBatchRequest msgB = buildBatchRequest(orderIdB, productCode, 3);

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

        // 等待: 两条消息都处理成功
        waitUntil(() -> {
            Inventory inv = inventoryRepository.findInventoryByProductCode(productCode)
                    .orElseThrow(() -> new RuntimeException("库存不存在"));
            String lockedStr = stringRedisTemplate.opsForValue()
                    .get(RedisKeys.lockedStockKey(productCode));
            long locked = lockedStr == null ? 0 : Long.parseLong(lockedStr);
            System.out.println("[DEBUG] CONFIRM场景2: sold=" + inv.getSoldStock()
                    + ", onHand=" + inv.getOnHandStock() + ", redisLocked=" + locked);
            return inv.getSoldStock() == 7 && locked == 0;
        }, 120000);

        Inventory updated = inventoryRepository.findInventoryByProductCode(productCode)
                .orElseThrow(() -> new AssertionError("商品应存在"));
        assertEquals(3, updated.getOnHandStock(), "V2-2: onHandStock=10-4-3=3");
        assertEquals(7, updated.getSoldStock(), "V2-2: sold=7");

        String lockedStr = stringRedisTemplate.opsForValue()
                .get(RedisKeys.lockedStockKey(productCode));
        assertEquals("0", lockedStr, "V2-2: Redis locked=0");

        Optional<InventoryOperation> opA = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderIdA, OperationType.CONFIRM);
        Optional<InventoryOperation> opB = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderIdB, OperationType.CONFIRM);
        assertTrue(opA.isPresent(), "V2-3: orderIdA的CONFIRM记录");
        assertTrue(opB.isPresent(), "V2-3: orderIdB的CONFIRM记录");
        assertEquals(OperationStatus.SUCCESS, opA.get().getOperationStatus());
        assertEquals(OperationStatus.SUCCESS, opB.get().getOperationStatus());
    }

    // ======================================================================
    //  场景 3 — UNLOCK 非法参数 → 不重试,快速失败
    // ======================================================================
    @Test
    @DisplayName("MQ场景3: UNLOCK业务异常(参数错)→不重试,快速失败+FAILED记录")
    void testUnlock_illegalArgument_noRetry_failed() throws InterruptedException {
        long productCode = 2003L;
        Inventory inventory = new Inventory(productCode, 5);
        inventoryRepository.saveAndFlush(inventory);
        simulateLockedState(productCode, 2, 5); // locked=2, avail=3

        long orderId = 5001L;
        InventoryBatchRequest request = buildBatchRequest(orderId, productCode, 5); // 5 > locked=2

        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_UNLOCK_EXCHANGE,
                RabbitMQConfig.INVENTORY_UNLOCK_ROUTING_KEY,
                request
        );

        waitUntil(() -> {
            return inventoryOperationRepository
                    .findByOrderIdAndOperationType(orderId, OperationType.UNLOCK)
                    .map(op -> op.getOperationStatus() != OperationStatus.PROCESSING)
                    .orElse(false);
        }, 15000);

        assertEquals("3", stringRedisTemplate.opsForValue()
                .get(RedisKeys.availableStockKey(productCode)), "Redis avail=3");
        assertEquals("2", stringRedisTemplate.opsForValue()
                .get(RedisKeys.lockedStockKey(productCode)), "Redis locked=2");

        Optional<InventoryOperation> op = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderId, OperationType.UNLOCK);
        assertTrue(op.isPresent(), "幂等记录应存在");
        assertEquals(OperationStatus.FAILED, op.get().getOperationStatus(), "状态应为FAILED");
    }

    // ======================================================================
    //  场景 4 — CONFIRM 幂等重复消息 → 仅执行一次
    //  同 orderId 25 次重复 → 幂等控制
    // ======================================================================
    @Test
    @DisplayName("MQ场景4: CONFIRM重复消息→幂等控制仅执行一次")
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
            Thread.sleep(10);
        }

        waitUntil(() -> {
            Inventory updated = inventoryRepository
                    .findInventoryByProductCode(productCode).orElseThrow();
            return updated.getSoldStock() == 4;
        }, 60000);

        Inventory updated = inventoryRepository
                .findInventoryByProductCode(productCode).orElseThrow();
        assertEquals(6, updated.getOnHandStock(), "onHandStock=6 (10-4)");
        assertEquals(4, updated.getSoldStock(), "sold=4");
        assertEquals("0", stringRedisTemplate.opsForValue()
                .get(RedisKeys.lockedStockKey(productCode)), "Redis locked=0");
        assertEquals(1, inventoryOperationRepository.findAll().size(), "仅1条幂等记录");
    }

    // ======================================================================
    //  场景 5 — UNLOCK: 多个商品批量解锁
    //  验证 batchUnlockStock 正确处理多个商品
    // ======================================================================
    @Test
    @DisplayName("MQ场景5: UNLOCK批量多商品解锁")
    void testUnlock_multipleProducts_batch() throws InterruptedException {
        long productCodeA = 3001L;
        long productCodeB = 3002L;
        inventoryRepository.saveAndFlush(new Inventory(productCodeA, 10));
        inventoryRepository.saveAndFlush(new Inventory(productCodeB, 20));
        simulateLockedState(productCodeA, 5, 10); // locked=5, avail=5
        simulateLockedState(productCodeB, 8, 20); // locked=8, avail=12

        long orderId = 7001L;
        InventoryBatchRequest request = new InventoryBatchRequest();
        try {
            Field orderIdField = InventoryBatchRequest.class.getDeclaredField("orderId");
            orderIdField.setAccessible(true);
            orderIdField.set(request, orderId);
            Field stockListField = InventoryBatchRequest.class.getDeclaredField("stockRequestList");
            stockListField.setAccessible(true);
            stockListField.set(request, List.of(
                    new StockRequest(productCodeA, 3),
                    new StockRequest(productCodeB, 4)
            ));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_UNLOCK_EXCHANGE,
                RabbitMQConfig.INVENTORY_UNLOCK_ROUTING_KEY,
                request
        );

        // 等待两个商品都解锁完毕
        waitUntil(() -> {
            String lockedA = stringRedisTemplate.opsForValue()
                    .get(RedisKeys.lockedStockKey(productCodeA));
            String lockedB = stringRedisTemplate.opsForValue()
                    .get(RedisKeys.lockedStockKey(productCodeB));
            long lockedALong = lockedA == null ? 0 : Long.parseLong(lockedA);
            long lockedBLong = lockedB == null ? 0 : Long.parseLong(lockedB);
            return lockedALong == 2 && lockedBLong == 4;
        }, 30000);

        assertEquals("8", stringRedisTemplate.opsForValue()
                .get(RedisKeys.availableStockKey(productCodeA)), "avail A=5+3=8");
        assertEquals("16", stringRedisTemplate.opsForValue()
                .get(RedisKeys.availableStockKey(productCodeB)), "avail B=12+4=16");
        assertEquals(2, inventoryOperationRepository.findAll().size(), "应有一条UNLOCK记录");
    }

    // ======================================================================
    //  场景 6 — UNLOCK: 同一商品但不同 orderId 并发解锁
    //  验证多个独立订单可以同时解锁同一商品（无 @Version 冲突）
    // ======================================================================
    @Test
    @DisplayName("MQ场景6: UNLOCK不同orderId同一商品→都能成功")
    void testUnlock_differentOrderIds_sameProduct() throws InterruptedException {
        long productCode = 4001L;
        inventoryRepository.saveAndFlush(new Inventory(productCode, 20));
        simulateLockedState(productCode, 10, 20); // locked=10, avail=10

        long orderIdA = 8001L;
        long orderIdB = 8002L;

        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_UNLOCK_EXCHANGE,
                RabbitMQConfig.INVENTORY_UNLOCK_ROUTING_KEY,
                buildBatchRequest(orderIdA, productCode, 3)
        );
        Thread.sleep(30);
        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_UNLOCK_EXCHANGE,
                RabbitMQConfig.INVENTORY_UNLOCK_ROUTING_KEY,
                buildBatchRequest(orderIdB, productCode, 4)
        );

        // 等待两个都完成（locked 应为 10-3-4=3）
        waitUntil(() -> {
            String lockedStr = stringRedisTemplate.opsForValue()
                    .get(RedisKeys.lockedStockKey(productCode));
            long locked = lockedStr == null ? 0 : Long.parseLong(lockedStr);
            return locked == 3;
        }, 30000);

        assertEquals("17", stringRedisTemplate.opsForValue()
                .get(RedisKeys.availableStockKey(productCode)), "avail=10+3+4=17");
        assertEquals("3", stringRedisTemplate.opsForValue()
                .get(RedisKeys.lockedStockKey(productCode)), "locked=3");

        // 验证两条幂等记录都是 SUCCESS
        List<InventoryOperation> ops = inventoryOperationRepository.findAll();
        assertEquals(2, ops.size(), "应有两条UNLOCK记录");
        assertTrue(ops.stream().allMatch(o -> o.getOperationStatus() == OperationStatus.SUCCESS),
                "所有记录应为SUCCESS");
    }

    // ======================================================================
    //  场景 7 — CONFIRM: 因库存不足而快速失败
    //  CONFIRM 时 Redis locked 不足 → 业务异常 → PROCESSING→FAILED
    //  注意: RuntimeException 子类默认不会被 @Retryable 重试
    // ======================================================================
    @Test
    @DisplayName("MQ场景7: CONFIRM库存不足→快速失败+FAILED记录")
    void testConfirm_insufficientLocked_failed() throws InterruptedException {
        long productCode = 5001L;
        inventoryRepository.saveAndFlush(new Inventory(productCode, 10));
        simulateLockedState(productCode, 1, 10); // locked=1, avail=9

        long orderId = 9001L;
        // 尝试 CONFIRM 5 件，但 locked 只有 1
        InventoryBatchRequest request = buildBatchRequest(orderId, productCode, 5);

        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_CONFIRM_EXCHANGE,
                RabbitMQConfig.INVENTORY_CONFIRM_ROUTING_KEY,
                request
        );

        waitUntil(() -> {
            return inventoryOperationRepository
                    .findByOrderIdAndOperationType(orderId, OperationType.CONFIRM)
                    .map(op -> op.getOperationStatus() != OperationStatus.PROCESSING)
                    .orElse(false);
        }, 15000);

        // Redis 不变
        assertEquals("9", stringRedisTemplate.opsForValue()
                .get(RedisKeys.availableStockKey(productCode)));
        assertEquals("1", stringRedisTemplate.opsForValue()
                .get(RedisKeys.lockedStockKey(productCode)));

        Optional<InventoryOperation> op = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderId, OperationType.CONFIRM);
        assertTrue(op.isPresent());
        assertEquals(OperationStatus.FAILED, op.get().getOperationStatus(), "应为FAILED");
    }

    // ======================================================================
    //  场景 8 — UNLOCK 后，再次向同一 orderId 发 CONFIRM
    //  验证不同的 operationType 可以分别独立幂等
    //  关键: CONFIRM 和 UNLOCK 的幂等 key 不同（orderId:CONFIRM vs orderId:UNLOCK）
    // ======================================================================
    @Test
    @DisplayName("MQ场景8: 同一orderId的UNLOCK和CONFIRM互不影响")
    void testUnlockAndConfirm_differentOperationTypes() throws InterruptedException {
        long productCode = 6001L;
        inventoryRepository.saveAndFlush(new Inventory(productCode, 10));
        simulateLockedState(productCode, 5, 10); // locked=5, avail=5

        long orderId = 5555L;

        // 先发 UNLOCK（解锁 2 件）
        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_UNLOCK_EXCHANGE,
                RabbitMQConfig.INVENTORY_UNLOCK_ROUTING_KEY,
                buildBatchRequest(orderId, productCode, 2)
        );

        waitUntil(() -> {
            String lockedStr = stringRedisTemplate.opsForValue()
                    .get(RedisKeys.lockedStockKey(productCode));
            return lockedStr != null && Long.parseLong(lockedStr) == 3; // 5-2=3
        }, 30000);

        // 再发 CONFIRM（确认 3 件）
        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_CONFIRM_EXCHANGE,
                RabbitMQConfig.INVENTORY_CONFIRM_ROUTING_KEY,
                buildBatchRequest(orderId, productCode, 3)
        );

        waitUntil(() -> {
            String lockedStr = stringRedisTemplate.opsForValue()
                    .get(RedisKeys.lockedStockKey(productCode));
            Inventory inv = inventoryRepository.findInventoryByProductCode(productCode).orElseThrow();
            long locked = lockedStr == null ? 0 : Long.parseLong(lockedStr);
            return locked == 0 && inv.getSoldStock() == 3;
        }, 30000);

        // 最终验证
        assertEquals("8", stringRedisTemplate.opsForValue()
                .get(RedisKeys.availableStockKey(productCode)), "avail=5+2=7(UNLOCK) -3(CONFIRM) ...");

        // 有两条不同的幂等记录
        List<InventoryOperation> ops = inventoryOperationRepository.findAll();
        assertEquals(2, ops.size(), "应有两条记录（UNLOCK+CONFIRM）");
        assertEquals(OperationStatus.SUCCESS,
                inventoryOperationRepository.findByOrderIdAndOperationType(orderId, OperationType.UNLOCK)
                        .get().getOperationStatus());
        assertEquals(OperationStatus.SUCCESS,
                inventoryOperationRepository.findByOrderIdAndOperationType(orderId, OperationType.CONFIRM)
                        .get().getOperationStatus());
    }

    // ======================================================================
    //  场景 9 — CONFIRM: 多次同 orderId 消息落在 Redis 层（幂等成功快速返回）
    //  验证第二次及以后的重复消息在 Redis 层就被过滤（不进入 DB）
    //  关键指标: DB 只有 1 条 SUCCESS 记录
    // ======================================================================
    @Test
    @DisplayName("MQ场景9: CONFIRM幂等→Redis层快速返回→DB仅1条记录")
    void testConfirm_idempotent_redisLevelCache() throws InterruptedException {
        long productCode = 7001L;
        inventoryRepository.saveAndFlush(new Inventory(productCode, 10));
        simulateLockedState(productCode, 4, 10);

        long orderId = 4444L;

        // 第一次发送
        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_CONFIRM_EXCHANGE,
                RabbitMQConfig.INVENTORY_CONFIRM_ROUTING_KEY,
                buildBatchRequest(orderId, productCode, 4)
        );

        // 等待第一次完成（soldStock=4）
        waitUntil(() -> {
            Inventory inv = inventoryRepository.findInventoryByProductCode(productCode).orElseThrow();
            return inv.getSoldStock() == 4;
        }, 30000);

        // 再次发送（幂等返回）
        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_CONFIRM_EXCHANGE,
                RabbitMQConfig.INVENTORY_CONFIRM_ROUTING_KEY,
                buildBatchRequest(orderId, productCode, 4)
        );

        // 等待第二次处理（应该很快返回，不修改任何数据）
        Thread.sleep(3000);

        // DB 应该仍然只有 1 条记录
        assertEquals(1, inventoryOperationRepository.findAll().size(), "DB应只有1条幂等记录");

        // 再多次重复发送验证
        for (int i = 0; i < 10; i++) {
            testRabbitTemplate.convertAndSend(
                    RabbitMQConfig.INVENTORY_CONFIRM_EXCHANGE,
                    RabbitMQConfig.INVENTORY_CONFIRM_ROUTING_KEY,
                    buildBatchRequest(orderId, productCode, 4)
            );
            Thread.sleep(5);
        }
        Thread.sleep(2000);
        assertEquals(1, inventoryOperationRepository.findAll().size(), "多次重复后DB依然只有1条记录");
    }

    // ======================================================================
    //  场景 10 — UNLOCK: 消息发到错误队列（验证路由隔离）
    //  UNLOCK 消息发到 CONFIRM routing key → 应被消费但业务执行失败？
    //  验证：Invalid routing key 导致消息无法投递
    // ======================================================================
    @Test
    @DisplayName("MQ场景10: 错误routing key→消息无法投递→队列无消息")
    void testWrongRoutingKey_messageLost() {
        long productCode = 8001L;
        inventoryRepository.saveAndFlush(new Inventory(productCode, 10));
        simulateLockedState(productCode, 3, 10);

        // UNLOCK 消息用 CONFIRM 的 routing key 发送到 UNLOCK exchange
        // 由于 DirectExchange 的 binding 只认自己的 routing key，这条消息会被丢掉
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_UNLOCK_EXCHANGE,
                RabbitMQConfig.INVENTORY_CONFIRM_ROUTING_KEY, // 错误 routing key
                buildBatchRequest(1111L, productCode, 1)
        );

        try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // Redis 状态不变（消息未到达消费者）
        assertEquals("7", stringRedisTemplate.opsForValue()
                .get(RedisKeys.availableStockKey(productCode)));
        assertEquals("3", stringRedisTemplate.opsForValue()
                .get(RedisKeys.lockedStockKey(productCode)));
    }

    // ======================================================================
    //  场景 11 — UNLOCK: Redis locked key 不存在时 → Lua 脚本返回 0 → FAILED
    //  验证极端情况下的容错
    // ======================================================================
    @Test
    @DisplayName("MQ场景11: UNLOCK时Redis locked不存在→FAILED")
    void testUnlock_noRedisKey_failed() throws InterruptedException {
        long productCode = 9001L;
        inventoryRepository.saveAndFlush(new Inventory(productCode, 10));
        // 不调用 simulateLockedState → Redis 中没有 locked key

        long orderId = 2222L;
        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_UNLOCK_EXCHANGE,
                RabbitMQConfig.INVENTORY_UNLOCK_ROUTING_KEY,
                buildBatchRequest(orderId, productCode, 1)
        );

        waitUntil(() -> {
            return inventoryOperationRepository
                    .findByOrderIdAndOperationType(orderId, OperationType.UNLOCK)
                    .map(op -> op.getOperationStatus() != OperationStatus.PROCESSING)
                    .orElse(false);
        }, 15000);

        Optional<InventoryOperation> op = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderId, OperationType.UNLOCK);
        assertTrue(op.isPresent());
        assertEquals(OperationStatus.FAILED, op.get().getOperationStatus(), "应为FAILED");
    }

    // ======================================================================
    //  场景 12 — UNLOCK: 两次不同 orderId，但其中一次有 PROCESSING 残留
    //  验证不同 orderId 间互不干扰
    // ======================================================================
    @Test
    @DisplayName("MQ场景12: UNLOCK不同orderId互不干扰")
    void testUnlock_differentOrders_independent() throws InterruptedException {
        long productCode = 10001L;
        inventoryRepository.saveAndFlush(new Inventory(productCode, 10));
        simulateLockedState(productCode, 6, 10); // locked=6, avail=4

        long orderA = 3301L;
        long orderB = 3302L;

        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_UNLOCK_EXCHANGE,
                RabbitMQConfig.INVENTORY_UNLOCK_ROUTING_KEY,
                buildBatchRequest(orderA, productCode, 2)
        );
        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_UNLOCK_EXCHANGE,
                RabbitMQConfig.INVENTORY_UNLOCK_ROUTING_KEY,
                buildBatchRequest(orderB, productCode, 3)
        );

        waitUntil(() -> {
            String lockedStr = stringRedisTemplate.opsForValue()
                    .get(RedisKeys.lockedStockKey(productCode));
            return lockedStr != null && Long.parseLong(lockedStr) == 1; // 6-2-3=1
        }, 30000);

        assertEquals("9", stringRedisTemplate.opsForValue()
                .get(RedisKeys.availableStockKey(productCode)), "avail=4+2+3=9");

        // 两条独立的 SUCCESS 记录
        assertEquals(2, inventoryOperationRepository.findAll().size());
    }

    // ======================================================================
    //  场景 13 — CONFIRM: 多个商品，部分失败→整体回滚
    //  验证 batchConfirmSale 中商品A成功但商品B失败时整体事务回滚
    // ======================================================================
    @Test
    @DisplayName("MQ场景13: CONFIRM多商品部分失败→整体回滚→FAILED")
    void testConfirm_batchPartialRollback_failed() throws InterruptedException {
        long productCodeA = 11001L;
        long productCodeB = 11002L;
        inventoryRepository.saveAndFlush(new Inventory(productCodeA, 10));
        inventoryRepository.saveAndFlush(new Inventory(productCodeB, 10));
        simulateLockedState(productCodeA, 5, 10); // locked=5 足够
        simulateLockedState(productCodeB, 1, 10); // locked=1 不足（要确认3）

        long orderId = 4401L;
        InventoryBatchRequest request = new InventoryBatchRequest();
        try {
            Field orderIdField = InventoryBatchRequest.class.getDeclaredField("orderId");
            orderIdField.setAccessible(true);
            orderIdField.set(request, orderId);
            Field stockListField = InventoryBatchRequest.class.getDeclaredField("stockRequestList");
            stockListField.setAccessible(true);
            stockListField.set(request, List.of(
                    new StockRequest(productCodeA, 3),
                    new StockRequest(productCodeB, 3) // locked只有1，会失败
            ));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        testRabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_CONFIRM_EXCHANGE,
                RabbitMQConfig.INVENTORY_CONFIRM_ROUTING_KEY,
                request
        );

        waitUntil(() -> {
            return inventoryOperationRepository
                    .findByOrderIdAndOperationType(orderId, OperationType.CONFIRM)
                    .map(op -> op.getOperationStatus() != OperationStatus.PROCESSING)
                    .orElse(false);
        }, 30000);

        // B失败导致整体回滚，A的locked应该恢复
        assertEquals("5", stringRedisTemplate.opsForValue()
                .get(RedisKeys.lockedStockKey(productCodeA)), "商品Alocked回滚");

        Optional<InventoryOperation> op = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderId, OperationType.CONFIRM);
        assertTrue(op.isPresent());
        assertEquals(OperationStatus.FAILED, op.get().getOperationStatus());
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
            Thread.sleep(200);
        }
        fail("Condition not met within " + timeoutMs + "ms timeout");
    }
}