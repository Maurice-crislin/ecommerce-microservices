package org.example.inventoryservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.inventoryservice.domain.InventoryOperation;
import org.example.inventoryservice.domain.OperationStatus;
import org.example.inventoryservice.domain.OperationType;
import org.example.inventoryservice.exception.OperationProcessingException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryIdempotencyExecutor {
    private final InventoryOperationService inventoryOperationService;
    private final StringRedisTemplate stringRedisTemplate;

    public static String IDEM_PREFIX = "idem:";
    private static final Duration REDIS_TTL = Duration.ofMinutes(30);
    private static final int MAX_RETRIES = 5;
    private static final long RETRY_DELAY_MS = 1500;
    private static final long RETRY_DELAY_MULTIPLIER = 2;

    /**
     * 执行幂等操作。
     *
     * 【设计说明】
     * 本方法不持有一级事务（没有 @Transactional），而是将事务边界委托给内部调用的各个方法：
     *
     *   {@link InventoryOperationService#getOrStartOperation} - 使用 REQUIRES_NEW 独立提交操作记录的创建
     *   {@link InventoryOperationService#markSuccess} / {@link InventoryOperationService#markFinalFailed}
     *   / {@link InventoryOperationService#markRetryableFailed} / {@link InventoryOperationService#deleteOperation}
     *   - 使用 REQUIRES_NEW 独立提交
     *   batchLogic - 由具体实现（如 batchUnlockStock/batchConfirmSale）自行管理事务
     *
     * 【重试策略 — 重要！】
     *
     * 重试仅覆盖两种异常：
     *
     *   ✅ OptimisticLockingFailureException（乐观锁冲突）
     *      → 这是【当前线程】业务执行中遇到的版本冲突。
     *      → 在 executeOnce 中先将 DB 记录标记为 FAILED_RETRYABLE（防止响应期间的死锁或进程崩溃），
     *        然后在此处删除该记录和 Redis key，从头重试（重新获取锁和执行）。
     *      → 删除是安全的，因为当前执行尚未成功。
     *
     *   ✅ OperationProcessingException（其他线程处理中）
     *      → 这是发现【其他线程】正在处理同一 orderId。
     *      → 【不应该】删除 Redis key 或 DB 记录！否则会破坏赢家线程的执行。
     *      → 正确做法：等待后重试，希望赢家线程已完成。
     *
     * 其他异常：标记为 FAILED_FINAL，不重试，直接抛出 → 死信队列。
     *
     * 【幂等控制】
     * Redis 层是第一道快速防线，DB 唯一约束是第二道兜底防线。
     * DB 是权威状态源，Redis 是缓存。先写 DB，后写 Redis 保持一致性。
     */
    public void executeWithIdempotency(Long orderId, OperationType operationType, Runnable batchLogic) {
        long delay = RETRY_DELAY_MS;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                executeOnce(orderId, operationType, batchLogic);
                return; // 成功
            } catch (OptimisticLockingFailureException e) {
                // 【乐观锁冲突】: 当前线程的业务遇到版本冲突
                // executeOnce 已将 DB 记录标记为 FAILED_RETRYABLE，Redis 设为 FAILED_RETRYABLE。
                if (attempt < MAX_RETRIES) {
                    log.warn("OptimisticLockFailure (attempt {}/{}), 等待 {}ms 后重试, orderId={}, type={}",
                            attempt, MAX_RETRIES, delay, orderId, operationType);
                    try {
                        Thread.sleep(delay);
                        delay *= RETRY_DELAY_MULTIPLIER;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Retry interrupted", ie);
                    }
                } else {
                    log.error("OptimisticLockFailure 重试耗尽 (attempts={}), 发送到死信队列 orderId={}, type={}",
                            MAX_RETRIES, orderId, operationType);
                    throw e;
                }
            } catch (OperationProcessingException e) {
                // 【其他线程处理中】: 另一个线程已经获取了执行权
                // 不能删除 Redis key 或 DB 记录！否则会破坏赢家线程的执行
                // 只等待后重试，希望赢家线程已完成
                if (attempt < MAX_RETRIES) {
                    log.warn("OperationProcessing (attempt {}/{}), 等待 {}ms 后重试, orderId={}, type={}",
                            attempt, MAX_RETRIES, delay, orderId, operationType);
                    try {
                        Thread.sleep(delay);
                        delay *= RETRY_DELAY_MULTIPLIER;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Retry interrupted", ie);
                    }
                } else {
                    log.error("OperationProcessing 重试耗尽 (attempts={}), 发送到死信队列 orderId={}, type={}",
                            MAX_RETRIES, orderId, operationType);
                    throw e;
                }
            }
        }
    }

    /**
     * 单次尝试执行幂等操作。
     * 包含：Redis 幂等校验 → DB 幂等校验 → 执行业务逻辑 → markSuccess
     */
    private void executeOnce(Long orderId, OperationType operationType, Runnable batchLogic) {
        String IDEM_OPEA_KEY = IDEM_PREFIX + orderId + ":" + operationType;

        // 1. Redis 层幂等校验
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                IDEM_OPEA_KEY, String.valueOf(OperationStatus.PROCESSING), REDIS_TTL);

        if (Boolean.FALSE.equals(acquired)) {
            // Redis key 已存在
            String statusFromRedis = stringRedisTemplate.opsForValue().get(IDEM_OPEA_KEY);
            if (statusFromRedis != null) {
                OperationStatus status = OperationStatus.valueOf(statusFromRedis);
                switch (status) {
                    case SUCCESS:
                        return; // 幂等返回成功
                    case FAILED_RETRYABLE:
                        // 前一次执行因可重试异常（如乐观锁冲突）失败
                        // 跳出 switch，继续到 DB 层检查最新状态
                        break;
                    case FAILED_FINAL:
                        throw new IllegalStateException("Previous operation failed (final) for order " + orderId);
                    case PROCESSING:
                        throw new OperationProcessingException(
                                "Inventory operation is still processing for order " + orderId);
                }
            }
            // 若幂等 key 恰好过期，放行去 DB 层
        }

        // 2. DB 层幂等校验
        try {
            inventoryOperationService.getOrStartOperation(orderId, operationType);
        } catch (DataIntegrityViolationException e) {
            // 唯一约束冲突，说明记录已存在
            InventoryOperation inventoryOperation = inventoryOperationService
                    .getOperationByOrderIdAndOperationType(orderId, operationType);

            switch (inventoryOperation.getOperationStatus()) {
                case SUCCESS:
                    this.setRedisStatus(IDEM_OPEA_KEY, OperationStatus.SUCCESS);
                    return; // 幂等返回成功
                case FAILED_RETRYABLE:
                    // 前一次执行因可重试异常失败，跳出 switch 重新执行业务
                    break;
                case FAILED_FINAL:
                    this.setRedisStatus(IDEM_OPEA_KEY, OperationStatus.FAILED_FINAL);
                    throw new IllegalStateException("Previous operation failed (final) for order " + orderId);
                case PROCESSING:
                    throw new OperationProcessingException(
                            "Inventory operation is still processing for order " + orderId);
            }
        }

        // 3. 执行业务逻辑
        try {
            batchLogic.run();
            // DB 是权威，先写 DB，再同步 Redis
            inventoryOperationService.markSuccess(orderId, operationType);
            this.setRedisStatus(IDEM_OPEA_KEY, OperationStatus.SUCCESS);
        } catch (OptimisticLockingFailureException e) {
            // 乐观锁冲突：标记为可重试失败
            // 先标记 DB 记录为 FAILED_RETRYABLE，再同步 Redis，
            // 防止重试前进程崩溃导致记录永远卡在 PROCESSING
            try {
                inventoryOperationService.markRetryableFailed(orderId, operationType);
                this.setRedisStatus(IDEM_OPEA_KEY, OperationStatus.FAILED_RETRYABLE);
            } catch (Exception ignored) {
                // 标记异常的记录不应掩盖原始乐观锁异常
            }
            throw e;
        } catch (Exception e) {
            // 其他业务异常（永久性失败）：标记为 FAILED_FINAL
            try {
                inventoryOperationService.markFinalFailed(orderId, operationType);
                this.setRedisStatus(IDEM_OPEA_KEY, OperationStatus.FAILED_FINAL);
            } catch (Exception ignored) {
                // markFailed 本身的异常不应掩盖原始异常
            }
            throw e;
        }
    }

    private void setRedisStatus(String key, OperationStatus status) {
        stringRedisTemplate.opsForValue().set(key, status.name(), REDIS_TTL);
    }
}