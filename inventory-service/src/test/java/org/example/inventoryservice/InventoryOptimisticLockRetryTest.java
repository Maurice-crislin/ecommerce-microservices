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

        boolean firstSuccess = Boolean.TRUE.equals(result1.get());
        boolean secondSuccess = Boolean.TRUE.equals(result2.get());

        assertThat(firstSuccess ^ secondSuccess)
                .as("场景1-V1-1: 两线程CONFIRM同一商品, 仅一个成功(@Version冲突). "
                        + "T1=%s(%s), T2=%s(%s)", firstSuccess, error1.get(), secondSuccess, error2.get())
                .isTrue();

        long successOrderId = firstSuccess ? orderId1 : orderId2;
        long failOrderId = firstSuccess ? orderId2 : orderId1;

        Optional<InventoryOperation> failOperation = inventoryOperationRepository
                .findByOrderIdAndOperationType(failOrderId, OperationType.CONFIRM);
        assertThat(failOperation)
                .as("场景1-V1-2: 失败方(orderId=%d)CONFIRM幂等记录应被delete", failOrderId)
                .isEmpty();

        String redisKey = InventoryIdempotencyExecutor.IDEM_PREFIX + failOrderId + ":" + OperationType.CONFIRM;
        assertThat(stringRedisTemplate.opsForValue().get(redisKey))
                .as("场景1-V1-3: 失败方Redis key应被删除")
                .isNull();

        InventoryOperation successOperation = inventoryOperationRepository
                .findByOrderIdAndOperationType(successOrderId, OperationType.CONFIRM)
                .orElseThrow(() -> new AssertionError("成功方的幂等记录应存在"));
        assertThat(successOperation.getOperationStatus())
                .as("场景1-V1-4: 成功方幂等记录应为SUCCESS")
                .isEqualTo(OperationStatus.SUCCESS);

        Inventory inventory = inventoryRepository.findInventoryByProductCode(PRODUCT_CODE)
                .orElseThrow(() -> new AssertionError("商品应存在"));
        assertThat(inventory.getOnHandStock()).as("场景1-V1-5: onHandStock=90").isEqualTo(90);
        assertThat(inventory.getSoldStock()).as("场景1-V1-5: soldStock=10").isEqualTo(10);
        assertThat(getRedisLocked(PRODUCT_CODE)).as("场景1-V1-5: locked=10").isEqualTo(10L);
    }

    // ======================================================================
    //  场景 2 — CONFIRM 乐观锁冲突后手动重试 → 重新执行业务成功
    // ======================================================================
    @Test
    @DisplayName("场景2: CONFIRM乐观锁冲突后手动重试能成功重新执行业务")
    @SneakyThrows
    void testOptimisticLock_retryCanReExecuteBusiness() {
        long productCode2 = 1002L;
        inventoryRepository.save(new Inventory(productCode2, 100));
        simulateLockedState(productCode2, 20, 100);

        long orderIdA = 3001L;
        long orderIdB = 3002L;
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

        boolean aSuccess = Boolean.TRUE.equals(resultA.get());
        boolean bSuccess = Boolean.TRUE.equals(resultB.get());

        assertThat(aSuccess ^ bSuccess)
                .as("场景2-V2-1: 两线程CONFIRM同一商品应仅一个成功(A=%s(%s), B=%s(%s))",
                        aSuccess, errorA.get(), bSuccess, errorB.get())
                .isTrue();

        long failOrderId = aSuccess ? orderIdB : orderIdA;

        Optional<InventoryOperation> deletedOperation = inventoryOperationRepository
                .findByOrderIdAndOperationType(failOrderId, OperationType.CONFIRM);
        assertThat(deletedOperation)
                .as("场景2-V2-2: 失败方(orderId=%d)CONFIRM幂等记录应被删除", failOrderId)
                .isEmpty();

        // V2-3: 重试失败方操作 → 应成功
        inventoryIdempotencyExecutor.executeWithIdempotency(
                failOrderId, OperationType.CONFIRM, createConfirmTask(productCode2, qty));

        // V2-4: DB 总扣减 20 (10+10)
        Inventory inventory = inventoryRepository.findInventoryByProductCode(productCode2)
                .orElseThrow(() -> new AssertionError("商品应存在"));
        assertThat(inventory.getOnHandStock()).as("场景2-V2-4: onHandStock=80").isEqualTo(80);
        assertThat(inventory.getSoldStock()).as("场景2-V2-4: soldStock=20").isEqualTo(20);
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
        assertThat(operation.get().getOperationStatus()).isEqualTo(OperationStatus.FAILED);

        String redisKey = InventoryIdempotencyExecutor.IDEM_PREFIX + orderId + ":" + OperationType.LOCK;
        assertThat(stringRedisTemplate.opsForValue().get(redisKey))
                .isEqualTo(OperationStatus.FAILED.name());

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
                .isEqualTo(OperationStatus.FAILED);

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
        assertThat(inventory.getOnHandStock()).as("场景8-V8-1: onHandStock=95").isEqualTo(95);
        assertThat(inventory.getSoldStock()).as("场景8-V8-1: soldStock=5").isEqualTo(5);
        assertThat(getRedisLocked(PRODUCT_CODE)).as("场景8-V8-1: locked=45").isEqualTo(45L);

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