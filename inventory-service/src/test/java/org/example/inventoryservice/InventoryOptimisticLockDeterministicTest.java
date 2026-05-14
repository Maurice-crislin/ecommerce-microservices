package org.example.inventoryservice;

import lombok.SneakyThrows;
import org.example.inventoryservice.config.RedisKeys;
import org.example.inventoryservice.domain.Inventory;
import org.example.inventoryservice.domain.InventoryOperation;
import org.example.inventoryservice.domain.OperationStatus;
import org.example.inventoryservice.domain.OperationType;
import org.example.inventoryservice.repository.InventoryOperationRepository;
import org.example.inventoryservice.repository.InventoryRepository;
import org.example.inventoryservice.service.InventoryIdempotencyExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * =====================================================================
 *  确定性乐观锁冲突重试验证
 * =====================================================================
 *
 *  测试方式：不依赖 MQ 或并发，直接通过 batchLogic 主动抛异常来触发
 *  executor 的 OptimisticLockingFailureException catch 分支。
 *
 *  验证目标:
 *    T1: batchLogic 抛 OptimisticLock → executor deleteOperation + 删 Redis key
 *    T2: 其他异常(IllegalArgument) → markFailed (形成对比)
 *    T3: 连续5次抛 OptimisticLock → deleteOperation → 第6次手动重试成功
 *    T4: 幂等并发控制 → 5线程同 orderId → 仅1次业务
 * =====================================================================
 */
@SpringBootTest
public class InventoryOptimisticLockDeterministicTest {

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryOperationRepository inventoryOperationRepository;

    @Autowired
    private InventoryIdempotencyExecutor executor;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final long PRODUCT_CODE = 3001L;
    private static final int INITIAL_STOCK = 100;

    @BeforeEach
    void setup() {
        Set<String> keys = stringRedisTemplate.keys(InventoryIdempotencyExecutor.IDEM_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
        // Clean Redis stock keys
        Set<String> stockKeys = stringRedisTemplate.keys("inventory:stock:*");
        if (stockKeys != null && !stockKeys.isEmpty()) {
            stringRedisTemplate.delete(stockKeys);
        }
        inventoryOperationRepository.deleteAll();
        inventoryRepository.deleteAll();
        inventoryOperationRepository.flush();
        inventoryRepository.flush();
        inventoryRepository.save(new Inventory(PRODUCT_CODE, INITIAL_STOCK));
    }

    // ==================================================================
    //  T1: batchLogic 抛 OptimisticLock → executor delete + 删 Redis
    //  注意：不手动设置 Redis key，让 executor 正常走到 batchLogic
    // ==================================================================
    @Test
    @DisplayName("T1: batchLogic抛OptimisticLock→deleteOp+删Redis(非FAILED)")
    @SneakyThrows
    void catchOptimisticLock_deletesRecord() {
        long orderId = 100L;

        try {
            executor.executeWithIdempotency(orderId, OperationType.LOCK, () -> {
                throw new OptimisticLockingFailureException("模拟乐观锁冲突");
            });
        } catch (OptimisticLockingFailureException expected) {
        }

        // ✅ 幂等记录被删除(不是 FAILED)
        Optional<InventoryOperation> op = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderId, OperationType.LOCK);
        assertThat(op)
                .as("T1: 幂等记录被deleteOperation()删除(非 markFailed)")
                .isEmpty();

        // ✅ Redis key 被删除
        String redisKey = InventoryIdempotencyExecutor.IDEM_PREFIX + orderId + ":" + OperationType.LOCK;
        assertThat(stringRedisTemplate.opsForValue().get(redisKey))
                .as("T1: Redis key 被删除")
                .isNull();

        // ✅ 库存不受影响
        Inventory inv = inventoryRepository.findInventoryByProductCode(PRODUCT_CODE)
                .orElseThrow(() -> new AssertionError("商品应存在"));
        assertThat(inv.getOnHandStock()).as("T1: 库存未变").isEqualTo(INITIAL_STOCK);
        assertThat(inv.getSoldStock()).as("T1: soldStock未变").isZero();
    }

    // ==================================================================
    //  T2: 其他异常 → markFailed (形成对比)
    // ==================================================================
    @Test
    @DisplayName("T2: 其他异常(IllegalArg)→markFailed(非delete,形成对比)")
    @SneakyThrows
    void otherException_markFailed() {
        long orderId = 200L;

        try {
            executor.executeWithIdempotency(orderId, OperationType.LOCK, () -> {
                throw new IllegalArgumentException("库存不足");
            });
        } catch (IllegalArgumentException expected) {
        }

        Optional<InventoryOperation> op = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderId, OperationType.LOCK);
        assertThat(op)
                .as("T2: 幂等记录应存在(非delete)")
                .isPresent();
        assertThat(op.get().getOperationStatus())
                .as("T2: 幂等记录为FAILED")
                .isEqualTo(OperationStatus.FAILED);

        String redisKey = InventoryIdempotencyExecutor.IDEM_PREFIX + orderId + ":" + OperationType.LOCK;
        assertThat(stringRedisTemplate.opsForValue().get(redisKey))
                .as("T2: Redis 为 FAILED")
                .isEqualTo(OperationStatus.FAILED.name());
    }

    // ==================================================================
    //  T3: 连续 5 次抛 OptimisticLock → 第 6 次执行业务成功
    //  在 new schema 中，LOCK 只操作 Redis，batchLogic 用 confirmSale()
    //  模拟一个会修改 DB + 触发乐观锁版本冲突的业务操作
    // ==================================================================
    @Test
    @DisplayName("T3: 连续5次OptimisticLock→第6次手动重试执行业务成功")
    @SneakyThrows
    void fiveFailures_sixthSuccess() {
        long orderId = 300L;

        for (int i = 1; i <= 6; i++) {
            final AtomicInteger attemptRef = new AtomicInteger(i);
            final int attempt = i;
            System.out.println("[T3] 第" + attempt + "次尝试调用 executor");

            Runnable batchLogic;
            if (attempt <= 5) {
                batchLogic = () -> {
                    System.out.println("[T3] 第" + attemptRef.get() + "次 batchLogic: 抛 OptimisticLock");
                    throw new OptimisticLockingFailureException("模拟冲突 #" + attemptRef.get());
                };
            } else {
                batchLogic = () -> {
                    System.out.println("[T3] 第6次 batchLogic: 执行业务成功 ✅");
                    Inventory inv = inventoryRepository
                            .findInventoryByProductCode(PRODUCT_CODE)
                            .orElseThrow(() -> new RuntimeException("库存不存在"));
                    // confirmSale 会修改 DB (onHandStock--, soldStock++), 触发乐观锁版本号变更
                    inv.confirmSale(10);
                    inventoryRepository.save(inv);
                };
            }

            try {
                executor.executeWithIdempotency(orderId, OperationType.LOCK, batchLogic);
                System.out.println("[T3] 第" + attempt + "次: 执行成功 ✅");
                break;
            } catch (OptimisticLockingFailureException e) {
                System.out.println("[T3] 第" + attempt + "次: 已 catch，继续重试");
                if (attempt == 6) {
                    throw e;
                }
            }
        }

        // ✅ 幂等记录 SUCCESS
        Optional<InventoryOperation> op = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderId, OperationType.LOCK);
        assertThat(op)
                .as("T3: 重试成功后的幂等记录应存在")
                .isPresent();
        assertThat(op.get().getOperationStatus())
                .as("T3: 幂等记录应为SUCCESS")
                .isEqualTo(OperationStatus.SUCCESS);

        // ✅ Redis SUCCESS
        String redisKey = InventoryIdempotencyExecutor.IDEM_PREFIX + orderId + ":" + OperationType.LOCK;
        assertThat(stringRedisTemplate.opsForValue().get(redisKey))
                .as("T3: Redis 应为SUCCESS")
                .isEqualTo(OperationStatus.SUCCESS.name());

        // ✅ 库存通过 confirmSale 修改了 10 件
        Inventory inv = inventoryRepository.findInventoryByProductCode(PRODUCT_CODE)
                .orElseThrow(() -> new AssertionError("商品应存在"));
        assertThat(inv.getSoldStock()).as("T3: soldStock=10").isEqualTo(10);
        assertThat(inv.getOnHandStock()).as("T3: onHandStock=90").isEqualTo(INITIAL_STOCK - 10);
    }

    // ==================================================================
    //  T4: 并发幂等 — 5线程同 orderId → 仅执行业务一次
    //  注意：batchLogic 内部用 Repository 直接操作，不嵌套 executor
    // ==================================================================
    @Test
    @DisplayName("T4: 5线程并发同orderId→幂等仅执行业务一次")
    @SneakyThrows
    void concurrentSameOrderId_businessOnce() {
        long orderId = 400L;
        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    executor.executeWithIdempotency(orderId, OperationType.LOCK, () -> {
                        // confirmSale simulates a business write to DB
                        Inventory inv = inventoryRepository
                                .findInventoryByProductCode(PRODUCT_CODE)
                                .orElseThrow(() -> new RuntimeException("库存不存在"));
                        inv.confirmSale(10);
                        inventoryRepository.save(inv);
                    });
                } catch (Exception e) {
                    System.out.println("[T4] 线程异常: " + e.getClass().getSimpleName());
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await();

        // 幂等记录应为 SUCCESS
        Optional<InventoryOperation> op = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderId, OperationType.LOCK);
        assertThat(op)
                .as("T4: 幂等记录应存在")
                .isPresent();
        assertThat(op.get().getOperationStatus())
                .as("T4: 幂等记录应为SUCCESS")
                .isEqualTo(OperationStatus.SUCCESS);

        // 库存只通过 confirmSale 修改一次 (10件)
        Inventory inv = inventoryRepository.findInventoryByProductCode(PRODUCT_CODE)
                .orElseThrow(() -> new AssertionError("商品应存在"));
        assertThat(inv.getSoldStock())
                .as("T4: soldStock=10(仅一次)")
                .isEqualTo(10);
        assertThat(inv.getOnHandStock())
                .as("T4: onHandStock=90(仅一次)")
                .isEqualTo(INITIAL_STOCK - 10);
    }
}