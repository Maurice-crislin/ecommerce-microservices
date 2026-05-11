package org.example.inventoryservice.service;

import lombok.RequiredArgsConstructor;
import org.example.inventoryservice.domain.InventoryOperation;
import org.example.inventoryservice.domain.OperationStatus;
import org.example.inventoryservice.domain.OperationType;
import org.example.inventoryservice.exception.OperationProcessingException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class InventoryIdempotencyExecutor {
    private final InventoryOperationService inventoryOperationService;
    private final StringRedisTemplate stringRedisTemplate;

    public static String IDEM_PREFIX = "idem:";
    private static final Duration REDIS_TTL = Duration.ofMinutes(30);



    /**
     * 执行幂等操作。
     *
     * 【设计说明】
     * 本方法不持有一级事务（没有 @Transactional），而是将事务边界委托给内部调用的各个方法：
     *
     *   {@link InventoryOperationService#getOrStartOperation} - 使用 REQUIRES_NEW 独立提交操作记录的创建
     *   {@link InventoryOperationService#markSuccess} / {@link InventoryOperationService#markFailed} - 使用 REQUIRES_NEW 独立提交状态更新
     *   batchLogic - 由具体实现（如 batchUnlockStock/batchConfirmSale）自行管理事务
     *
     * 这样做的好处是：即使 batchLogic 抛出异常回滚了它自己的事务，PROCESSING 记录仍然保留在数据库中，
     * 并且会被 markFailed 正确标记为 FAILED，不会产生"孤魂野鬼"记录。
     *
     * Redis 层是第一道快速防线，DB 唯一约束是第二道兜底防线。
     * DB 是权威状态源，Redis 是缓存。先写 DB，后写 Redis 保持一致性。
     */
    public void executeWithIdempotency(Long orderId, OperationType operationType, Runnable batchLogic) {
        String IDEM_OPEA_KET = IDEM_PREFIX + orderId + ":" +operationType;
        Boolean acquired  = stringRedisTemplate.opsForValue().setIfAbsent(IDEM_OPEA_KET, String.valueOf(OperationStatus.PROCESSING),REDIS_TTL);

        // 1.redis idem
        if(Boolean.FALSE.equals(acquired)) {
            // 没抢到执行权 redis里面已有幂等key
            String status_from_redis = stringRedisTemplate.opsForValue().get(IDEM_OPEA_KET);
            if (status_from_redis != null) {

                OperationStatus status = OperationStatus.valueOf(status_from_redis);
                switch (status) {
                    case SUCCESS:
                        return; // 幂等返回
                    case FAILED:
                        throw new IllegalStateException("Previous operation failed for order " + orderId);
                    case PROCESSING:
                        throw new OperationProcessingException("Inventory operation is still processing for order " + orderId);
                }
            } // 若幂等key恰好过期,放行去db层就好
        }

        // 2. db idem
        try {
            inventoryOperationService.getOrStartOperation(orderId, operationType);
        } catch (DataIntegrityViolationException e) {
            // 唯一约束冲突 → 重复操作请求，查询已有记录并判断状态
            InventoryOperation inventoryOperation = inventoryOperationService
                    .getOperationByOrderIdAndOperationType(orderId, operationType);

            switch (inventoryOperation.getOperationStatus()) {
                case SUCCESS:
                    // redis数据不一致,导致你从redis拿到执行权,但实际任务已经执行了
                    this.setRedisStatus(IDEM_OPEA_KET, OperationStatus.SUCCESS);
                    return; // 幂等返回
                case FAILED:
                    // redis数据不一致,导致你从redis拿到执行权,但实际任务已经执行了
                    this.setRedisStatus(IDEM_OPEA_KET, OperationStatus.FAILED);
                    throw new IllegalStateException("Previous operation failed for order " + orderId);
                case PROCESSING:
                    throw new OperationProcessingException("Inventory operation is still processing for order " + orderId);
            }
        }

        try {
            batchLogic.run();
            // DB 是权威，先写 DB，再同步 Redis
            inventoryOperationService.markSuccess(orderId, operationType);
            this.setRedisStatus(IDEM_OPEA_KET, OperationStatus.SUCCESS);
        } catch (Exception e) {
            // 捕获所有异常，确保 PROCESSING → FAILED 转换一定被执行
            try {
                inventoryOperationService.markFailed(orderId, operationType);
                this.setRedisStatus(IDEM_OPEA_KET, OperationStatus.FAILED);
            } catch (Exception ignored) {
                // markFailed 本身的异常（如记录被删除）不应掩盖原始异常
            }
            throw e;
        }
    }

    private void setRedisStatus(String key, OperationStatus status) {
        stringRedisTemplate.opsForValue().set(key, status.name(), REDIS_TTL);
    }
}