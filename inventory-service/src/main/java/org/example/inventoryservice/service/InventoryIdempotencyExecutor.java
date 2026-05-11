package org.example.inventoryservice.service;

import lombok.RequiredArgsConstructor;
import org.example.inventoryservice.domain.InventoryOperation;
import org.example.inventoryservice.domain.OperationStatus;
import org.example.inventoryservice.domain.OperationType;
import org.example.inventoryservice.exception.OperationProcessingException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InventoryIdempotencyExecutor {
    private final InventoryOperationService inventoryOperationService;

    /**
     * 执行幂等操作。
     * <p>
     * 【设计说明】
     * 本方法不持有一级事务（没有 @Transactional），而是将事务边界委托给内部调用的各个方法：
     * <ul>
     *   <li>{@link InventoryOperationService#getOrStartOperation} - 使用 REQUIRES_NEW 独立提交操作记录的创建</li>
     *   <li>{@link InventoryOperationService#markSuccess} / {@link InventoryOperationService#markFailed} - 使用 REQUIRES_NEW 独立提交状态更新</li>
     *   <li>batchLogic - 由具体实现（如 batchUnlockStock/batchConfirmSale）自行管理事务</li>
     * </ul>
     * 这样做的好处是：即使 batchLogic 抛出异常回滚了它自己的事务，PROCESSING 记录仍然保留在数据库中，
     * 并且会被 markFailed 正确标记为 FAILED，不会产生"孤魂野鬼"记录。
     */
    public void executeWithIdempotency(Long orderId, OperationType operationType, Runnable batchLogic) {
        try {
            inventoryOperationService.getOrStartOperation(orderId, operationType);
        } catch (DataIntegrityViolationException e) {
            // 唯一约束冲突 → 重复操作请求，查询已有记录并判断状态
            InventoryOperation inventoryOperation = inventoryOperationService
                    .getOperationByOrderIdAndOperationType(orderId, operationType);

            switch (inventoryOperation.getOperationStatus()) {
                case SUCCESS:
                    return; // 幂等返回
                case FAILED:
                    throw new IllegalStateException("Previous operation failed for order " + orderId);
                case PROCESSING:
                    throw new OperationProcessingException("Inventory operation is still processing for order " + orderId);
            }
        }

        try {
            batchLogic.run();
            inventoryOperationService.markSuccess(orderId, operationType);
        } catch (Exception e) {
            // 捕获所有异常，确保 PROCESSING → FAILED 转换一定被执行
            try {
                inventoryOperationService.markFailed(orderId, operationType);
            } catch (Exception ignored) {
                // markFailed 本身的异常（如记录被删除）不应掩盖原始异常
            }
            throw e;
        }
    }
}