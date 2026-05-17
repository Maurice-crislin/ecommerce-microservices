package org.example.inventoryservice.messaging;

import lombok.RequiredArgsConstructor;
import org.common.inventory.dto.InventoryBatchRequest;
import org.example.inventoryservice.exception.OperationProcessingException;
import org.example.inventoryservice.service.InventoryService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InventoryEventListener {
    private final InventoryService inventoryService;

     /**
      * OperationProcessingException 另一线程正在处理中( Redis/DB 查到 PROCESSING 状态时) 临时竞争状态，等赢家完成即可
        OptimisticLockingFailureException 乐观锁冲突（batchLogic 抛出 是Inventory业务代码的 不是幂等控制表的） 临时冲突，重试即可
      */
    @RabbitListener(queues = RabbitMQConfig.INVENTORY_UNLOCK_QUEUE,containerFactory = "rabbitListenerContainerFactory")
    @Retryable(
            retryFor = {OptimisticLockingFailureException.class, OperationProcessingException.class},
            maxAttempts = 5,
            backoff = @Backoff(delay = 2000, multiplier = 2)
    )
    public void handleUnlockStock( InventoryBatchRequest inventoryBatchEvent) {

        inventoryService.batchUnlockStockWithIdempotency(inventoryBatchEvent);

    }

    // @Retryable 实现本地内存重试（不经过 MQ 重新投递，节省网络和 MQ 性能）
    @RabbitListener(queues = RabbitMQConfig.INVENTORY_CONFIRM_QUEUE,containerFactory = "rabbitListenerContainerFactory")
    @Retryable(
            retryFor = {OptimisticLockingFailureException.class, OperationProcessingException.class},
            maxAttempts = 5,
            backoff = @Backoff(delay = 2000, multiplier = 2)
    )
    public void handleConfirmStock( InventoryBatchRequest inventoryBatchEvent) {
        inventoryService.batchConfirmSaleWithIdempotency(inventoryBatchEvent);
    }
}
//异常	场景	@Retryable 行为	是否应该重试
//OperationProcessingException	另一线程正在处理中	✅ 会重试	✅ 临时竞争状态，等赢家完成即可
//OptimisticLockingFailureException	乐观锁冲突（batchLogic 抛出）	✅ 会重试	✅ 临时冲突，重试即可
//IllegalStateException("Previous operation failed")	上一次操作 FAILED	❌ 不会重试	❌ 永久失败，重试多少次都是 FAILED
//IllegalArgumentException("Product not found")	商品不存在	❌ 不会重试	❌ 数据错误，重试多少次都一样
//OperationProcessingException (新增 Redis 版本)	Redis 查到 PROCESSING	✅ 会重试	✅ 同现有逻辑
