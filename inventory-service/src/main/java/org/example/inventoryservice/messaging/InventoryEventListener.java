package org.example.inventoryservice.messaging;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.common.inventory.dto.InventoryBatchRequest;
import org.example.inventoryservice.service.InventoryService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryEventListener {
    private final InventoryService inventoryService;

    /**
     * 处理库存解锁消息。
     *
     * 不设置 @Retryable，当业务执行抛出异常时：
     *   1. executeWithIdempotency 内部捕获异常并设置 FAILED 状态（针对永久性失败）
     *      或删除幂等记录和 Redis key 后重新抛出（针对乐观锁冲突）
     *   2. 无论哪种情况，最终异常传播到此方法
     *   3. RabbitMQ 容器配置了 setDefaultRequeueRejected(false)，消息自动进入死信队列
     *   4. 死信队列 (inventory_unlock_queue.dlq) 可用于后续人工处理或自动补偿
     *
     * 【死信队列配置】
     *   DLX: inventory.dlx.exchange
     *   DLQ: inventory_unlock_queue.dlq (for UNLOCK)
     *        inventory_confirm_queue.dlq (for CONFIRM)
     */
    @RabbitListener(queues = RabbitMQConfig.INVENTORY_UNLOCK_QUEUE, containerFactory = "rabbitListenerContainerFactory")
    public void handleUnlockStock(InventoryBatchRequest inventoryBatchEvent) {
        log.info("收到unlock消息，orderId: {}", inventoryBatchEvent.getOrderId());
        log.info("========== 进入 batchUnlockStockWithIdempotency 方法 ==========");
        inventoryService.batchUnlockStockWithIdempotency(inventoryBatchEvent);
        log.info("unlock业务执行完成, orderId: {}", inventoryBatchEvent.getOrderId());
    }

    /**
     * 处理库存确认消息。
     * 异常处理机制同 handleUnlockStock。
     */
    @RabbitListener(queues = RabbitMQConfig.INVENTORY_CONFIRM_QUEUE)
    public void handleConfirmStock(InventoryBatchRequest inventoryBatchEvent) {
        log.info("收到confirm消息，orderId: {}", inventoryBatchEvent.getOrderId());
        log.info("========== 进入 batchConfirmSaleWithIdempotency 方法 ==========");
        inventoryService.batchConfirmSaleWithIdempotency(inventoryBatchEvent);
        log.info("confirm业务执行完成, orderId: {}", inventoryBatchEvent.getOrderId());
    }
}
/** 异常	场景	@Retryable 行为	是否应该重试
 OperationProcessingException	幂等表查到另一线程正在处理中	✅ 会重试	✅ 临时竞争状态，等赢家完成即可
 OperationProcessingException (新增 Redis 版本)	Redis 查到另一线程正在 PROCESSING	✅ 会重试	✅ 临时竞争状态，等赢家完成即可
 OptimisticLockingFailureException	乐观锁冲突（batchLogic 抛出）	✅ 会重试	✅ 临时冲突，重试即可
 IllegalStateException("Previous operation failed")	上一次操作 FAILED	❌ 不会重试	❌ 永久失败，重试多少次都是 FAILED
 IllegalArgumentException("Product not found")	商品不存在	❌ 不会重试	❌ 数据错误，重试多少次都一样
 */
