package org.example.inventoryservice;

import lombok.extern.slf4j.Slf4j;
import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.dto.StockRequest;
import org.example.inventoryservice.config.RedisKeys;
import org.example.inventoryservice.domain.Inventory;
import org.example.inventoryservice.domain.InventoryOperation;
import org.example.inventoryservice.domain.OperationStatus;
import org.example.inventoryservice.domain.OperationType;
import org.example.inventoryservice.repository.InventoryOperationRepository;
import org.example.inventoryservice.repository.InventoryRepository;
import org.example.inventoryservice.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 直接调用 InventoryService 的集成测试（不通过 MQ 入口）
 *
 * 与 InventoryMQIntegrationTest 逻辑完全相同，只是通过 service 方法直接调用
 * batchUnlockStockWithIdempotency() 和 batchConfirmSaleWithIdempotency()
 *
 * 注意：
 *   - 不需要管理 RabbitMQ 监听器、队列清理等 MQ 基础设施
 *   - 并发场景（场景1、2、6）使用 CompletableFuture 模拟 MQ 并发消费
 *   - 环境清理只涉及 DB 和 Redis，不包括 MQ 队列
 */
@Slf4j
@SpringBootTest
public class InventoryServiceDirectIntegrationTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryOperationRepository inventoryOperationRepository;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setup() {
        // ======== 1. 清理 Redis ========
        Set<String> idempotencyKeys = stringRedisTemplate.keys("idem:*");
        if (idempotencyKeys != null && !idempotencyKeys.isEmpty()) {
            stringRedisTemplate.delete(idempotencyKeys);
        }
        Set<String> stockKeys = stringRedisTemplate.keys("inventory:stock:*");
        if (stockKeys != null && !stockKeys.isEmpty()) {
            stringRedisTemplate.delete(stockKeys);
        }

        // ======== 2. 清理数据库 ========
        inventoryOperationRepository.deleteAll();
        inventoryRepository.deleteAll();
        inventoryOperationRepository.flush();
        inventoryRepository.flush();

        // 等待清理完成
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void simulateLockedState(Long productCode, int lockedQty, int onHandStock) {
        stringRedisTemplate.opsForValue().set(
                RedisKeys.availableStockKey(productCode), String.valueOf(onHandStock - lockedQty));
        stringRedisTemplate.opsForValue().set(
                RedisKeys.lockedStockKey(productCode), String.valueOf(lockedQty));
    }

    // ======================================================================
    //  场景 0 — 简化测试：单条消息解锁
    // ======================================================================
    @Test
    @DisplayName("0 简化测试：单条消息解锁")
    void testSimpleUnlock() {
        long productCode = 70001L;
        Inventory inventory = new Inventory(productCode, 5);
        inventoryRepository.saveAndFlush(inventory);
        simulateLockedState(productCode, 4, 5);

        long orderId = System.currentTimeMillis();
        int qty = 2;
        InventoryBatchRequest msg = buildBatchRequest(orderId, productCode, qty);

        inventoryService.batchUnlockStockWithIdempotency(msg);

        // 验证结果
        String lockedStr = stringRedisTemplate.opsForValue()
                .get(RedisKeys.lockedStockKey(productCode));
        long locked = lockedStr == null ? 0 : Long.parseLong(lockedStr);

        assertEquals(2, locked, "locked应该是2");
        assertEquals("3", stringRedisTemplate.opsForValue()
                .get(RedisKeys.availableStockKey(productCode)), "avail=3 (1+2)");
    }

    // ======================================================================
    //  场景 1 — UNLOCK 两消息同一 orderId (OPERATION_PROCESSING retry)
    //  验证同 orderId 并发时，幂等执行器能够自动重试并幂等返回
    // ======================================================================
    @Test
    @DisplayName("场景1: UNLOCK同orderId→OperationProcessing→Retry→成功")
    void testUnlock_operationProcessing_retry_success() throws Exception {
        long productCode = 2001L;

        Inventory inventory = new Inventory(productCode, 5);
        inventoryRepository.saveAndFlush(inventory);
        simulateLockedState(productCode, 4, 5);

        long orderId = 1001L;
        int qty = 2;
        InventoryBatchRequest msg = buildBatchRequest(orderId, productCode, qty);

        // 并发调用两次：后一次会碰到前一次的 PROCESSING 状态，触发重试
        CompletableFuture<Void> future1 = CompletableFuture.runAsync(() -> {
            inventoryService.batchUnlockStockWithIdempotency(msg);
        });
        CompletableFuture<Void> future2 = CompletableFuture.runAsync(() -> {
            inventoryService.batchUnlockStockWithIdempotency(msg);
        });

        CompletableFuture.allOf(future1, future2).get();

        waitUntil(() -> {
            String lockedStr = stringRedisTemplate.opsForValue()
                    .get(RedisKeys.lockedStockKey(productCode));
            long locked = lockedStr == null ? 0 : Long.parseLong(lockedStr);
            // 只解锁一次: 4-2=2
            return locked == 2;
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
    //  场景 2 — CONFIRM 乐观锁冲突 → Retry重试 → 最终成功
    //  两个不同 orderId CONFIRM 同一商品 → @Version 冲突
    //
    //  项目已修复：batchConfirmSale() 通过 entityManager.flush()
    //  提前将 DB 变更 flush 到数据库，乐观锁冲突在方法体内（Redis 操作之前）爆发。
    // ======================================================================
    @Test
    @DisplayName("场景2: CONFIRM乐观锁冲突→Retry→最终成功")
    void testConfirm_optimisticLock_retry_success() throws Exception {
        long productCode = 2002L;
        Inventory inventory = new Inventory(productCode, 10);
        inventoryRepository.saveAndFlush(inventory);
        simulateLockedState(productCode, 7, 10); // locked=7, avail=3

        long orderIdA = 2001L;
        long orderIdB = 2002L;

        InventoryBatchRequest msgA = buildBatchRequest(orderIdA, productCode, 4);
        InventoryBatchRequest msgB = buildBatchRequest(orderIdB, productCode, 3);

        // 并发调用，制造乐观锁冲突
        CompletableFuture<Void> futureA = CompletableFuture.runAsync(() -> {
            inventoryService.batchConfirmSaleWithIdempotency(msgA);
        });
        CompletableFuture<Void> futureB = CompletableFuture.runAsync(() -> {
            inventoryService.batchConfirmSaleWithIdempotency(msgB);
        });

        CompletableFuture.allOf(futureA, futureB).get();

        // 等待两个操作都成功完成
        waitUntil(() -> {
            Inventory inv = inventoryRepository.findInventoryByProductCode(productCode)
                    .orElseThrow(() -> new RuntimeException("库存不存在"));
            String lockedStr = stringRedisTemplate.opsForValue()
                    .get(RedisKeys.lockedStockKey(productCode));
            long locked = lockedStr == null ? 0 : Long.parseLong(lockedStr);
            log.info("[DEBUG] CONFIRM场景2: sold={}, onHand={}, redisLocked={}",
                    inv.getSoldStock(), inv.getOnHandStock(), locked);
            return inv.getSoldStock() == 7 && locked == 0;
        }, 120000);

        Inventory updated = inventoryRepository.findInventoryByProductCode(productCode)
                .orElseThrow(() -> new AssertionError("商品应存在"));
        assertEquals(3, updated.getOnHandStock(), "onHandStock=10-4-3=3");
        assertEquals(7, updated.getSoldStock(), "sold=7");

        String lockedStr = stringRedisTemplate.opsForValue()
                .get(RedisKeys.lockedStockKey(productCode));
        assertEquals("0", lockedStr, "Redis locked=0");

        Optional<InventoryOperation> opA = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderIdA, OperationType.CONFIRM);
        Optional<InventoryOperation> opB = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderIdB, OperationType.CONFIRM);
        assertTrue(opA.isPresent(), "orderIdA的CONFIRM记录");
        assertTrue(opB.isPresent(), "orderIdB的CONFIRM记录");
        assertEquals(OperationStatus.SUCCESS, opA.get().getOperationStatus());
        assertEquals(OperationStatus.SUCCESS, opB.get().getOperationStatus());
    }

    // ======================================================================
    //  场景 3 — UNLOCK 非法参数 → 不重试,快速失败
    // ======================================================================
    @Test
    @DisplayName("场景3: UNLOCK业务异常(参数错)→不重试,快速失败+FAILED_FINAL记录")
    void testUnlock_illegalArgument_noRetry_failed() {
        long productCode = 2003L;
        Inventory inventory = new Inventory(productCode, 5);
        inventoryRepository.saveAndFlush(inventory);
        simulateLockedState(productCode, 2, 5); // locked=2, avail=3

        long orderId = 5001L;
        // 解锁数量 5 > locked=2 → 业务异常
        InventoryBatchRequest request = buildBatchRequest(orderId, productCode, 5);

        assertThrows(Exception.class, () -> {
            inventoryService.batchUnlockStockWithIdempotency(request);
        });

        // Redis 状态不变
        assertEquals("3", stringRedisTemplate.opsForValue()
                .get(RedisKeys.availableStockKey(productCode)), "Redis avail=3");
        assertEquals("2", stringRedisTemplate.opsForValue()
                .get(RedisKeys.lockedStockKey(productCode)), "Redis locked=2");

        // 幂等记录标记为 FAILED_FINAL
        Optional<InventoryOperation> op = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderId, OperationType.UNLOCK);
        assertTrue(op.isPresent(), "幂等记录应存在");
        assertEquals(OperationStatus.FAILED_FINAL, op.get().getOperationStatus(), "状态应为FAILED_FINAL");
    }

    // ======================================================================
    //  场景 4 — CONFIRM 幂等重复调用 → 仅执行一次
    //  同 orderId 25 次重复 → 幂等控制
    // ======================================================================
    @Test
    @DisplayName("场景4: CONFIRM重复调用→幂等控制仅执行一次")
    void testConfirmIdempotency_idempotentOnce() {
        long productCode = 1003L;
        Inventory inventory = new Inventory(productCode, 10);
        inventoryRepository.saveAndFlush(inventory);
        simulateLockedState(productCode, 4, 10); // locked=4, avail=6

        long orderId = 3L;
        InventoryBatchRequest request = buildBatchRequest(orderId, productCode, 4);

        // 25 次重复调用
        for (int i = 0; i < 25; i++) {
            inventoryService.batchConfirmSaleWithIdempotency(request);
        }

        // 最终验证
        Inventory updated = inventoryRepository
                .findInventoryByProductCode(productCode).orElseThrow();
        assertEquals(6, updated.getOnHandStock(), "onHandStock=6 (10-4)");
        assertEquals(4, updated.getSoldStock(), "sold=4");
        assertEquals("0", stringRedisTemplate.opsForValue()
                .get(RedisKeys.lockedStockKey(productCode)), "Redis locked=0");

        // DB 只有 1 条幂等记录
        assertEquals(1, inventoryOperationRepository.findAll().size(), "仅1条幂等记录");
    }

    // ======================================================================
    //  场景 5 — UNLOCK: 多个商品批量解锁
    //  验证 batchUnlockStock 正确处理多个商品
    // ======================================================================
    @Test
    @DisplayName("场景5: UNLOCK批量多商品解锁")
    void testUnlock_multipleProducts_batch() {
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

        inventoryService.batchUnlockStockWithIdempotency(request);

        // 验证两个商品的解锁结果
        assertEquals("8", stringRedisTemplate.opsForValue()
                .get(RedisKeys.availableStockKey(productCodeA)), "avail A=5+3=8");
        assertEquals("16", stringRedisTemplate.opsForValue()
                .get(RedisKeys.availableStockKey(productCodeB)), "avail B=12+4=16");
        assertEquals("2", stringRedisTemplate.opsForValue()
                .get(RedisKeys.lockedStockKey(productCodeA)), "locked A=5-3=2");
        assertEquals("4", stringRedisTemplate.opsForValue()
                .get(RedisKeys.lockedStockKey(productCodeB)), "locked B=8-4=4");

        // 一个(orderId, operationType)对应一条幂等记录，无论包含几个商品
        assertEquals(1, inventoryOperationRepository.findAll().size(), "应有一条UNLOCK记录");
    }

    // ======================================================================
    //  场景 6 — UNLOCK: 同一商品但不同 orderId 并发解锁
    //  验证多个独立订单可以同时解锁同一商品（无 @Version 冲突）
    // ======================================================================
    @Test
    @DisplayName("场景6: UNLOCK不同orderId同一商品→都能成功")
    void testUnlock_differentOrderIds_sameProduct() throws Exception {
        long productCode = 4001L;
        inventoryRepository.saveAndFlush(new Inventory(productCode, 20));
        simulateLockedState(productCode, 10, 20); // locked=10, avail=10

        long orderIdA = 8001L;
        long orderIdB = 8002L;

        InventoryBatchRequest msgA = buildBatchRequest(orderIdA, productCode, 3);
        InventoryBatchRequest msgB = buildBatchRequest(orderIdB, productCode, 4);

        // 并发调用两个不同的 orderId
        CompletableFuture<Void> futureA = CompletableFuture.runAsync(() -> {
            inventoryService.batchUnlockStockWithIdempotency(msgA);
        });
        CompletableFuture<Void> futureB = CompletableFuture.runAsync(() -> {
            inventoryService.batchUnlockStockWithIdempotency(msgB);
        });
        CompletableFuture.allOf(futureA, futureB).get();

        // 验证：locked = 10 - 3 - 4 = 3
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
    //  CONFIRM 时 Redis locked 不足 → 业务异常 → PROCESSING→FAILED_FINAL
    //  注意: RuntimeException 子类默认不会被 @Retryable 重试
    // ======================================================================
    @Test
    @DisplayName("场景7: CONFIRM库存不足→快速失败+FAILED_FINAL记录")
    void testConfirm_insufficientLocked_failed() {
        long productCode = 5001L;
        inventoryRepository.saveAndFlush(new Inventory(productCode, 10));
        simulateLockedState(productCode, 1, 10); // locked=1, avail=9

        long orderId = 9001L;
        // 尝试 CONFIRM 5 件，但 locked 只有 1
        InventoryBatchRequest request = buildBatchRequest(orderId, productCode, 5);

        assertThrows(Exception.class, () -> {
            inventoryService.batchConfirmSaleWithIdempotency(request);
        });

        // Redis 状态不变
        assertEquals("9", stringRedisTemplate.opsForValue()
                .get(RedisKeys.availableStockKey(productCode)));
        assertEquals("1", stringRedisTemplate.opsForValue()
                .get(RedisKeys.lockedStockKey(productCode)));

        // 幂等记录标记为 FAILED_FINAL
        Optional<InventoryOperation> op = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderId, OperationType.CONFIRM);
        assertTrue(op.isPresent());
        assertEquals(OperationStatus.FAILED_FINAL, op.get().getOperationStatus(), "应为FAILED_FINAL");
    }

    // ======================================================================
    //  场景 8 — UNLOCK 后，再次向同一 orderId 发 CONFIRM
    //  验证不同的 operationType 可以分别独立幂等
    //  关键: CONFIRM 和 UNLOCK 的幂等 key 不同（orderId:CONFIRM vs orderId:UNLOCK）
    // ======================================================================
    @Test
    @DisplayName("场景8: 同一orderId的UNLOCK和CONFIRM互不影响")
    void testUnlockAndConfirm_differentOperationTypes() throws InterruptedException {
        long productCode = 6001L;
        inventoryRepository.saveAndFlush(new Inventory(productCode, 10));
        simulateLockedState(productCode, 5, 10); // locked=5, avail=5

        long orderId = 5555L;

        // 先发 UNLOCK（解锁 2 件）
        inventoryService.batchUnlockStockWithIdempotency(
                buildBatchRequest(orderId, productCode, 2));

        // 再发 CONFIRM（确认 3 件）
        inventoryService.batchConfirmSaleWithIdempotency(
                buildBatchRequest(orderId, productCode, 3));

        // 等待 CONFIRM 完成
        waitUntil(() -> {
            Inventory inv = inventoryRepository.findInventoryByProductCode(productCode)
                    .orElseThrow();
            String lockedStr = stringRedisTemplate.opsForValue()
                    .get(RedisKeys.lockedStockKey(productCode));
            long locked = lockedStr == null ? 0 : Long.parseLong(lockedStr);
            return locked == 0 && inv.getSoldStock() == 3;
        }, 30000);

        // 最终验证
        // UNLOCK 操作: avail=5+2=7 (Redis avail++, locked--)
        // CONFIRM 操作: 修改 locked (locked--) 和 DB (onHandStock--, soldStock++)，不修改 avail
        // 所以最终 avail = 7
        assertEquals("7", stringRedisTemplate.opsForValue()
                .get(RedisKeys.availableStockKey(productCode)),
                "avail=5(初始)+2(UNLOCK)=7, CONFIRM不改变avail");

        Inventory inv = inventoryRepository.findInventoryByProductCode(productCode)
                .orElseThrow();
        assertEquals(7, inv.getOnHandStock(), "onHandStock=10-3=7");
        assertEquals(3, inv.getSoldStock(), "sold=3");

        // 有两条不同的幂等记录
        List<InventoryOperation> ops = inventoryOperationRepository.findAll();
        assertEquals(2, ops.size(), "应有两条记录（UNLOCK+CONFIRM）");
        assertEquals(OperationStatus.SUCCESS,
                inventoryOperationRepository
                        .findByOrderIdAndOperationType(orderId, OperationType.UNLOCK)
                        .get().getOperationStatus());
        assertEquals(OperationStatus.SUCCESS,
                inventoryOperationRepository
                        .findByOrderIdAndOperationType(orderId, OperationType.CONFIRM)
                        .get().getOperationStatus());
    }

    // ======================================================================
    //  场景 9 — CONFIRM: 多次同 orderId 调用落在 Redis 层（幂等成功快速返回）
    //  验证第二次及以后的重复调用在 Redis 层就被过滤（不进入 DB）
    //  关键指标: DB 只有 1 条 SUCCESS 记录
    // ======================================================================
    @Test
    @DisplayName("场景9: CONFIRM幂等→Redis层快速返回→DB仅1条记录")
    void testConfirm_idempotent_redisLevelCache() {
        long productCode = 7001L;
        inventoryRepository.saveAndFlush(new Inventory(productCode, 10));
        simulateLockedState(productCode, 4, 10);

        long orderId = 4444L;

        // 第一次调用
        inventoryService.batchConfirmSaleWithIdempotency(
                buildBatchRequest(orderId, productCode, 4));

        // 再次调用（幂等返回）
        inventoryService.batchConfirmSaleWithIdempotency(
                buildBatchRequest(orderId, productCode, 4));

        // DB 应该仍然只有 1 条记录
        assertEquals(1, inventoryOperationRepository.findAll().size(), "DB应只有1条幂等记录");

        // 再多次重复调用验证
        for (int i = 0; i < 10; i++) {
            inventoryService.batchConfirmSaleWithIdempotency(
                    buildBatchRequest(orderId, productCode, 4));
        }
        assertEquals(1, inventoryOperationRepository.findAll().size(),
                "多次重复后DB依然只有1条记录");
    }

    // ======================================================================
    //  场景 10 — UNLOCK: Redis locked key 不存在时 → Lua 脚本返回 0 → FAILED_FINAL
    //  验证极端情况下的容错
    // ======================================================================
    @Test
    @DisplayName("场景10: UNLOCK时Redis locked不存在→FAILED_FINAL")
    void testUnlock_noRedisKey_failed() {
        long productCode = 9001L;
        inventoryRepository.saveAndFlush(new Inventory(productCode, 10));
        // 不调用 simulateLockedState → Redis 中没有 locked key

        long orderId = 2222L;
        assertThrows(Exception.class, () -> {
            inventoryService.batchUnlockStockWithIdempotency(
                    buildBatchRequest(orderId, productCode, 1));
        });

        // 幂等记录标记为 FAILED_FINAL
        Optional<InventoryOperation> op = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderId, OperationType.UNLOCK);
        assertTrue(op.isPresent());
        assertEquals(OperationStatus.FAILED_FINAL, op.get().getOperationStatus(), "应为FAILED_FINAL");
    }

    // ======================================================================
    //  场景 11 — UNLOCK: 两次不同 orderId 互不干扰
    //  验证不同 orderId 间的操作独立性
    // ======================================================================
    @Test
    @DisplayName("场景11: UNLOCK不同orderId互不干扰")
    void testUnlock_differentOrders_independent() {
        long productCode = 10001L;
        inventoryRepository.saveAndFlush(new Inventory(productCode, 10));
        simulateLockedState(productCode, 6, 10); // locked=6, avail=4

        long orderA = 3301L;
        long orderB = 3302L;

        inventoryService.batchUnlockStockWithIdempotency(
                buildBatchRequest(orderA, productCode, 2));
        inventoryService.batchUnlockStockWithIdempotency(
                buildBatchRequest(orderB, productCode, 3));

        // 验证最终状态：locked = 6 - 2 - 3 = 1
        assertEquals("9", stringRedisTemplate.opsForValue()
                .get(RedisKeys.availableStockKey(productCode)), "avail=4+2+3=9");
        assertEquals("1", stringRedisTemplate.opsForValue()
                .get(RedisKeys.lockedStockKey(productCode)), "locked=6-2-3=1");

        // 两条独立的 SUCCESS 记录
        List<InventoryOperation> ops = inventoryOperationRepository.findAll();
        assertEquals(2, ops.size());
        assertTrue(ops.stream().allMatch(o -> o.getOperationStatus() == OperationStatus.SUCCESS));
    }

    // ======================================================================
    //  场景 12 — CONFIRM: 多个商品，部分失败→整体回滚
    //  验证 batchConfirmSale 中商品A成功但商品B失败时整体事务回滚
    // ======================================================================
    @Test
    @DisplayName("场景12: CONFIRM多商品部分失败→整体回滚→FAILED_FINAL")
    void testConfirm_batchPartialRollback_failed() {
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

        assertThrows(Exception.class, () -> {
            inventoryService.batchConfirmSaleWithIdempotency(request);
        });

        // B失败导致整体回滚，A的locked应该恢复
        assertEquals("5", stringRedisTemplate.opsForValue()
                .get(RedisKeys.lockedStockKey(productCodeA)), "商品Alocked回滚");

        // 幂等记录标记为 FAILED_FINAL
        Optional<InventoryOperation> op = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderId, OperationType.CONFIRM);
        assertTrue(op.isPresent());
        assertEquals(OperationStatus.FAILED_FINAL, op.get().getOperationStatus());
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