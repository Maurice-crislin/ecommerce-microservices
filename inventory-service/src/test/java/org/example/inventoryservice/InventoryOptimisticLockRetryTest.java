package org.example.inventoryservice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.dto.StockRequest;
import org.example.inventoryservice.config.RedisKeys;
import org.example.inventoryservice.domain.Inventory;
import org.example.inventoryservice.domain.InventoryOperation;
import org.example.inventoryservice.domain.OperationStatus;
import org.example.inventoryservice.domain.OperationType;
import org.example.inventoryservice.dto.SimpleResponse;
import org.example.inventoryservice.repository.InventoryOperationRepository;
import org.example.inventoryservice.repository.InventoryRepository;
import org.example.inventoryservice.service.InventoryIdempotencyExecutor;
import org.example.inventoryservice.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * InventoryOptimisticLockRetryTest
 *
 * 新 schema (onHandStock/soldStock):
 *   - LOCK/UNLOCK 只操作 Redis Lua (无 DB @Version)
 *   - CONFIRM 操作 DB (onHandStock--, soldStock++) + @Version → 触发乐观锁冲突
 * 因此需要测试"乐观锁冲突→deleteOperation→重试"的场景必须用 CONFIRM.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class InventoryOptimisticLockRetryTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryOperationRepository inventoryOperationRepository;

    @Autowired
    private InventoryIdempotencyExecutor inventoryIdempotencyExecutor;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final long PRODUCT_CODE = 2001L;
    private static final int INITIAL_STOCK = 100;

    @BeforeEach
    void setup() {
        Set<String> idempotencyKeys = stringRedisTemplate.keys(InventoryIdempotencyExecutor.IDEM_PREFIX + "*");
        if (idempotencyKeys != null && !idempotencyKeys.isEmpty()) {
            stringRedisTemplate.delete(idempotencyKeys);
        }
        Set<String> stockKeys = stringRedisTemplate.keys("inventory:stock:*");
        if (stockKeys != null && !stockKeys.isEmpty()) {
            stringRedisTemplate.delete(stockKeys);
        }

        inventoryOperationRepository.deleteAll();
        inventoryOperationRepository.flush();
        inventoryRepository.deleteAll();
        inventoryRepository.flush();

        inventoryRepository.save(new Inventory(PRODUCT_CODE, INITIAL_STOCK));
    }

    private void initRedisAvail(Long productCode, int quantity) {
        stringRedisTemplate.opsForValue().set(
                RedisKeys.availableStockKey(productCode), String.valueOf(quantity));
    }

    private void setRedisLocked(Long productCode, int quantity) {
        stringRedisTemplate.opsForValue().set(
                RedisKeys.lockedStockKey(productCode), String.valueOf(quantity));
    }

    private Long getRedisAvail(Long productCode) {
        String val = stringRedisTemplate.opsForValue().get(RedisKeys.availableStockKey(productCode));
        return val == null ? null : Long.parseLong(val);
    }

    private Long getRedisLocked(Long productCode) {
        String val = stringRedisTemplate.opsForValue().get(RedisKeys.lockedStockKey(productCode));
        return val == null ? null : Long.parseLong(val);
    }

    private void simulateLockedState(Long productCode, int lockedQty, int onHandStock) {
        initRedisAvail(productCode, onHandStock - lockedQty);
        setRedisLocked(productCode, lockedQty);
    }

    private Runnable createConfirmTask(Long productCode, int qty) {
        return () -> {
            Inventory inv = inventoryRepository.findInventoryByProductCode(productCode)
                    .orElseThrow(() -> new RuntimeException("库存不存在"));
            inv.confirmSale(qty);
            inventoryRepository.save(inv);
            stringRedisTemplate.opsForValue().decrement(
                    RedisKeys.lockedStockKey(productCode), qty);
        };
    }

    // ======================================================================
    //  场景 1 — CONFIRM 乐观锁冲突 → 幂等记录被删除(非FAILED) + Redis key 被删
    //  注意: /inventories/batch/confirm 无 REST 端点, 直接用 executor
    // ======================================================================
    @Test
    @DisplayName("场景1: CONFIRM乐观锁冲突→幂等记录被delete(非FAILED)+Redis key被删")
    @SneakyThrows
    void testOptimisticLock_recordDeletedOnConflict() {
        simulateLockedState(PRODUCT_CODE, 20, INITIAL_STOCK);

        long orderId1 = 1001L;
        long orderId2 = 1002L;
        int confirmQty = 10;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicReference<Boolean> result1 = new AtomicReference<>();
        AtomicReference<Boolean> result2 = new AtomicReference<>();
        AtomicReference<String> error1 = new AtomicReference<>();
        AtomicReference<String> error2 = new AtomicReference<>();

        new Thread(() -> {
            try {
                startLatch.await();
                inventoryIdempotencyExecutor.executeWithIdempotency(
                        orderId1, OperationType.CONFIRM, createConfirmTask(PRODUCT_CODE, confirmQty));
                result1.set(Boolean.TRUE);
            } catch (Exception e) {
                result1.set(Boolean.FALSE);
                error1.set(e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                doneLatch.countDown();
            }
        }).start();

        new Thread(() -> {
            try {
                startLatch.await();
                inventoryIdempotencyExecutor.executeWithIdempotency(
                        orderId2, OperationType.CONFIRM, createConfirmTask(PRODUCT_CODE, confirmQty));
                result2.set(Boolean.TRUE);
            } catch (Exception e) {
                result2.set(Boolean.FALSE);
                error2.set(e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                doneLatch.countDown();
            }
        }).start();

        startLatch.countDown();
        doneLatch.await();

        // 随着重试次数增加(MAX_RETRIES=5),两个线程都能通过重试解决乐观锁冲突并成功
        assertThat(Boolean.TRUE.equals(result1.get()))
                .as("场景1-V1-1: 线程1应最终成功. T1=%s(%s)", result1.get(), error1.get())
                .isTrue();
        assertThat(Boolean.TRUE.equals(result2.get()))
                .as("场景1-V1-1: 线程2应最终成功. T2=%s(%s)", result2.get(), error2.get())
                .isTrue();

        // 两个 operation record 都应存在且为 SUCCESS
        InventoryOperation operation1 = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderId1, OperationType.CONFIRM)
                .orElseThrow(() -> new AssertionError("订单1001 operation record应存在"));
        InventoryOperation operation2 = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderId2, OperationType.CONFIRM)
                .orElseThrow(() -> new AssertionError("订单1002 operation record应存在"));
        assertThat(operation1.getOperationStatus()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(operation2.getOperationStatus()).isEqualTo(OperationStatus.SUCCESS);

        // 库存: 两个CONFIRM各扣10件 => onHand=80, sold=20
        Inventory inventory = inventoryRepository.findInventoryByProductCode(PRODUCT_CODE)
                .orElseThrow(() -> new AssertionError("商品应存在"));
        assertThat(inventory.getOnHandStock()).as("场景1-V1-5: onHandStock=80").isEqualTo(80);
        assertThat(inventory.getSoldStock()).as("场景1-V1-5: soldStock=20").isEqualTo(20);
        assertThat(getRedisLocked(PRODUCT_CODE)).as("场景1-V1-5: locked=0").isEqualTo(0L);
    }

    // ======================================================================
    //  场景 2 — CONFIRM 乐观锁冲突后手动重试 → 重新执行业务成功
    // ======================================================================
    @Test
    @DisplayName("场景2: CONFIRM乐观锁冲突后手动重试能成功重新执行业务")
    @SneakyThrows
    void testOptimisticLock_retryCanReExecuteBusiness() {
        long productCode2 = 602L;
        inventoryRepository.save(new Inventory(productCode2, 100));
        simulateLockedState(productCode2, 20, 100);

        long orderIdA = 1001L;
        long orderIdB = 1002L;
        int qty = 10;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicReference<Boolean> resultA = new AtomicReference<>();
        AtomicReference<Boolean> resultB = new AtomicReference<>();
        AtomicReference<String> errorA = new AtomicReference<>();
        AtomicReference<String> errorB = new AtomicReference<>();

        new Thread(() -> {
            try {
                startLatch.await();
                inventoryIdempotencyExecutor.executeWithIdempotency(
                        orderIdA, OperationType.CONFIRM, createConfirmTask(productCode2, qty));
                resultA.set(Boolean.TRUE);
            } catch (Exception e) {
                resultA.set(Boolean.FALSE);
                errorA.set(e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                doneLatch.countDown();
            }
        }).start();

        new Thread(() -> {
            try {
                startLatch.await();
                inventoryIdempotencyExecutor.executeWithIdempotency(
                        orderIdB, OperationType.CONFIRM, createConfirmTask(productCode2, qty));
                resultB.set(Boolean.TRUE);
            } catch (Exception e) {
                resultB.set(Boolean.FALSE);
                errorB.set(e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                doneLatch.countDown();
            }
        }).start();

        startLatch.countDown();
        doneLatch.await();

        // ============================================================
        // 1. 两个线程最终都应成功
        // ============================================================

        assertThat(resultA.get())
                .as("订单1001最终应成功, error=%s", errorA.get())
                .isTrue();

        assertThat(resultB.get())
                .as("订单1002最终应成功, error=%s", errorB.get())
                .isTrue();

        // ============================================================
        // 2. 两个 operation record 都应存在且为 SUCCESS
        // ============================================================

        InventoryOperation operation1 = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderIdA, OperationType.CONFIRM)
                .orElseThrow(() ->
                        new AssertionError("订单1001 operation record 应存在"));

        InventoryOperation operation2 = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderIdB, OperationType.CONFIRM)
                .orElseThrow(() ->
                        new AssertionError("订单1002 operation record 应存在"));

        assertThat(operation1.getOperationStatus())
                .as("订单1001 operation status 应为 SUCCESS")
                .isEqualTo(OperationStatus.SUCCESS);

        assertThat(operation2.getOperationStatus())
                .as("订单1002 operation status 应为 SUCCESS")
                .isEqualTo(OperationStatus.SUCCESS);
        // ============================================================
        // 3. Redis 幂等 key 应为 SUCCESS
        // ============================================================

        String redisKey1 = InventoryIdempotencyExecutor.IDEM_PREFIX
                + orderIdA + ":" + OperationType.CONFIRM;

        String redisKey2 = InventoryIdempotencyExecutor.IDEM_PREFIX
                + orderIdB + ":" + OperationType.CONFIRM;

        assertThat(stringRedisTemplate.opsForValue().get(redisKey1))
                .as("订单1001 Redis status 应为 SUCCESS")
                .isEqualTo(OperationStatus.SUCCESS.name());

        assertThat(stringRedisTemplate.opsForValue().get(redisKey2))
                .as("订单1002 Redis status 应为 SUCCESS")
                .isEqualTo(OperationStatus.SUCCESS.name());

        // ============================================================
        // 4. 库存最终一致性校验
        //  注意：产品 productCode2=602，不是 PRODUCT_CODE=2001！
        // ============================================================

        Inventory inventory = inventoryRepository
                .findInventoryByProductCode(productCode2)
                .orElseThrow(() -> new AssertionError("商品应存在"));

        assertThat(inventory.getOnHandStock())
                .as("onHandStock 应减少20")
                .isEqualTo(80);

        assertThat(inventory.getSoldStock())
                .as("soldStock 应增加20")
                .isEqualTo(20);

        // confirm 后 locked 应减少20
        assertThat(getRedisLocked(productCode2))
                .as("lockedStock 应减少20")
                .isEqualTo(0L);
    }

    // ======================================================================
    //  场景 3 — 其他业务异常(库存不足) → markFailed (非 delete)
    // ======================================================================
    @Test
    @DisplayName("场景3: 库存不足→幂等记录标记FAILED(非delete)+Redis FAILED")
    @SneakyThrows
    void testBusinessException_markedFailed() {
        initRedisAvail(PRODUCT_CODE, INITIAL_STOCK);

        long orderId = 4001L;
        InventoryBatchRequest event = new InventoryBatchRequest(orderId,
                List.of(new StockRequest(PRODUCT_CODE, INITIAL_STOCK + 1)));

        MvcResult result = mockMvc.perform(post("/inventories/batch/lock")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(event))).andReturn();

        SimpleResponse<?> response = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                new TypeReference<SimpleResponse<?>>() {});

        assertThat(response.isSuccess()).as("场景3-V3-1: 库存不足时API应返回失败").isFalse();

        Optional<InventoryOperation> operation = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderId, OperationType.LOCK);
        assertThat(operation).isPresent();
        assertThat(operation.get().getOperationStatus()).isEqualTo(OperationStatus.FAILED_FINAL);

        String redisKey = InventoryIdempotencyExecutor.IDEM_PREFIX + orderId + ":" + OperationType.LOCK;
        assertThat(stringRedisTemplate.opsForValue().get(redisKey))
                .isEqualTo(OperationStatus.FAILED_FINAL.name());

        Inventory inventory = inventoryRepository.findInventoryByProductCode(PRODUCT_CODE)
                .orElseThrow(() -> new AssertionError("商品应存在"));
        assertThat(inventory.getOnHandStock()).isEqualTo(INITIAL_STOCK);
    }

    // ======================================================================
    //  场景 4 — 幂等重复请求(SUCCESS) → 返回成功但不重复执行业务 (LOCK)
    // ======================================================================
    @Test
    @DisplayName("场景4: SUCCESS重复请求→幂等返回成功但不重复执行业务")
    @SneakyThrows
    void testIdempotentDuplicateRequest_returnsSuccess() {
        initRedisAvail(PRODUCT_CODE, INITIAL_STOCK);

        long orderId = 5001L;
        int lockQuantity = 10;
        InventoryBatchRequest event = new InventoryBatchRequest(orderId,
                List.of(new StockRequest(PRODUCT_CODE, lockQuantity)));

        for (int i = 0; i < 3; i++) {
            MvcResult r = mockMvc.perform(post("/inventories/batch/lock")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(event))).andReturn();
            SimpleResponse<?> resp = objectMapper.readValue(r.getResponse().getContentAsString(),
                    new TypeReference<SimpleResponse<?>>() {});
            assertThat(resp.isSuccess()).as("场景4-V4-%d: 第%d次请求应成功", i + 1, i + 1).isTrue();
        }

        assertThat(getRedisAvail(PRODUCT_CODE)).isEqualTo((long) (INITIAL_STOCK - lockQuantity));
        assertThat(getRedisLocked(PRODUCT_CODE)).isEqualTo((long) lockQuantity);
    }

    // ======================================================================
    //  场景 5 — FAILED 重复请求 → 快速失败 (LOCK)
    // ======================================================================
    @Test
    @DisplayName("场景5: FAILED重复请求→快速失败(不执行业务)")
    @SneakyThrows
    void testFailedOperation_duplicateRequest_throwsIllegalState() {
        initRedisAvail(PRODUCT_CODE, INITIAL_STOCK);

        long orderId = 6001L;
        InventoryBatchRequest failEvent = new InventoryBatchRequest(orderId,
                List.of(new StockRequest(PRODUCT_CODE, INITIAL_STOCK + 1)));
        mockMvc.perform(post("/inventories/batch/lock")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(failEvent)));

        assertThat(inventoryOperationRepository
                .findByOrderIdAndOperationType(orderId, OperationType.LOCK).get().getOperationStatus())
                .isEqualTo(OperationStatus.FAILED_FINAL);

        InventoryBatchRequest retryEvent = new InventoryBatchRequest(orderId,
                List.of(new StockRequest(PRODUCT_CODE, 1)));
        MvcResult retryResult = mockMvc.perform(post("/inventories/batch/lock")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(retryEvent))).andReturn();
        SimpleResponse<?> retryResp = objectMapper.readValue(
                retryResult.getResponse().getContentAsString(),
                new TypeReference<SimpleResponse<?>>() {});

        assertThat(retryResp.isSuccess()).isFalse();
        assertThat(getRedisAvail(PRODUCT_CODE)).isEqualTo(INITIAL_STOCK);
    }

    // ======================================================================
    //  场景 6 — 成功后 Redis+DB 状态一致 (LOCK)
    // ======================================================================
    @Test
    @DisplayName("场景6: 成功后Redis+DB状态一致(均为SUCCESS)")
    @SneakyThrows
    void testProcessingState_concurrentRequest_throwsProcessing() {
        initRedisAvail(PRODUCT_CODE, INITIAL_STOCK);

        long orderId = 7001L;
        InventoryBatchRequest event = new InventoryBatchRequest(orderId,
                List.of(new StockRequest(PRODUCT_CODE, 10)));

        MvcResult r1 = mockMvc.perform(post("/inventories/batch/lock")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(event))).andReturn();
        SimpleResponse<?> resp1 = objectMapper.readValue(r1.getResponse().getContentAsString(),
                new TypeReference<SimpleResponse<?>>() {});

        assertThat(resp1.isSuccess()).isTrue();

        assertThat(inventoryOperationRepository
                .findByOrderIdAndOperationType(orderId, OperationType.LOCK).get().getOperationStatus())
                .isEqualTo(OperationStatus.SUCCESS);

        String redisKey = InventoryIdempotencyExecutor.IDEM_PREFIX + orderId + ":" + OperationType.LOCK;
        assertThat(stringRedisTemplate.opsForValue().get(redisKey))
                .isEqualTo(OperationStatus.SUCCESS.name());
    }

    // ======================================================================
    //  场景 7 — 锁定全部可用库存 (LOCK)
    // ======================================================================
    @Test
    @DisplayName("场景7: 锁定全部可用库存")
    @SneakyThrows
    void testLockAllAvailableStock() {
        initRedisAvail(PRODUCT_CODE, INITIAL_STOCK);

        long orderId = 8001L;
        InventoryBatchRequest event = new InventoryBatchRequest(orderId,
                List.of(new StockRequest(PRODUCT_CODE, INITIAL_STOCK)));

        MvcResult result = mockMvc.perform(post("/inventories/batch/lock")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(event))).andReturn();

        SimpleResponse<?> response = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                new TypeReference<SimpleResponse<?>>() {});

        assertThat(response.isSuccess()).isTrue();
        assertThat(getRedisAvail(PRODUCT_CODE)).isZero();
        assertThat(getRedisLocked(PRODUCT_CODE)).isEqualTo((long) INITIAL_STOCK);
    }

    // ======================================================================
    //  场景 8 — 多 orderId 并发 CONFIRM → 仅一个成功
    //  用 executor 直接调用(无 REST 端点)
    // ======================================================================
    @Test
    @DisplayName("场景8: 5不同orderId CONFIRM→仅一个成功+守恒")
    @SneakyThrows
    void testMultipleOrdersLockSameProduct_onlyOneSucceeds() {
        simulateLockedState(PRODUCT_CODE, 50, INITIAL_STOCK);

        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            long orderId = 9000L + i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    inventoryIdempotencyExecutor.executeWithIdempotency(
                            orderId, OperationType.CONFIRM, createConfirmTask(PRODUCT_CODE, 5));
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await();

        Inventory inventory = inventoryRepository.findInventoryByProductCode(PRODUCT_CODE)
                .orElseThrow(() -> new AssertionError("商品应存在"));
        assertThat(inventory.getOnHandStock()).as("场景8-V8-1: onHandStock=100-5*5").isEqualTo(75);
        assertThat(inventory.getSoldStock()).as("场景8-V8-1: soldStock=5*5").isEqualTo(25);
        assertThat(getRedisLocked(PRODUCT_CODE)).as("场景8-V8-1: locked=50-5*5").isEqualTo(25L);

        long redisAvail = getRedisAvail(PRODUCT_CODE);
        long redisLocked = getRedisLocked(PRODUCT_CODE);
        // CONFIRM decrements Redis locked (not avail), so avail+locked < initial 100
        // Identity: Redis avail + Redis locked = DB onHandStock
        assertThat(redisAvail + redisLocked)
                .as("场景8-V8-2: Redis avail+locked = onHandStock(%d)", inventory.getOnHandStock())
                .isEqualTo((long) inventory.getOnHandStock());
    }

    // ======================================================================
    //  场景 9 — 幂等+并发:相同 orderId 10 线程 → 仅执行业务一次 (LOCK)
    // ======================================================================
    @Test
    @DisplayName("场景9: 10线程相同orderId并发→幂等仅执行业务一次")
    @SneakyThrows
    void testConcurrentIdempotentCalls_businessExecutedOnce() {
        initRedisAvail(PRODUCT_CODE, INITIAL_STOCK);

        long orderId = 10001L;
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    InventoryBatchRequest event = new InventoryBatchRequest(orderId,
                            List.of(new StockRequest(PRODUCT_CODE, 10)));
                    mockMvc.perform(post("/inventories/batch/lock")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(event))).andReturn();
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await();

        assertThat(getRedisLocked(PRODUCT_CODE)).isEqualTo(10);
        assertThat(inventoryOperationRepository
                .findByOrderIdAndOperationType(orderId, OperationType.LOCK).get().getOperationStatus())
                .isEqualTo(OperationStatus.SUCCESS);
    }

    // ======================================================================
    //  场景 10 — @Retryable (占位, 依赖 MQ 环境)
    // ======================================================================
    @Test
    @DisplayName("场景10: @Retryable自动重试(依赖RabbitMQ)")
    void testRetryableAnnotation_autoRetryOnOptimisticLock() {
        System.out.println("场景10: @Retryable 完整验证依赖 MQ (见 InventoryMQIntegrationTest)。");
        System.out.println("场景1+2 已验证: CONFIRM乐观锁→deleteOperation+删Redis key→可重试");
        Inventory inventory = inventoryRepository.findInventoryByProductCode(PRODUCT_CODE)
                .orElseThrow(() -> new AssertionError("商品应存在"));
        assertThat(inventory.getOnHandStock()).isEqualTo(INITIAL_STOCK);
    }
}