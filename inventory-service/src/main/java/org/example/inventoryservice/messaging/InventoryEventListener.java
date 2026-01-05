package org.example.inventoryservice.messaging;

import lombok.RequiredArgsConstructor;
import org.common.inventory.dto.InventoryBatchRequest;
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

    @RabbitListener(queues = RabbitMQConfig.INVENTORY_UNLOCK_QUEUE)
    @Retryable(
            value = {OptimisticLockingFailureException.class},
            maxAttempts = 5,
            backoff = @Backoff(delay = 2000, multiplier = 2)
    )
    public void handleUnlockStock( InventoryBatchRequest inventoryBatchEvent) {

        inventoryService.batchUnlockStockWithIdempotency(inventoryBatchEvent);

    }
    @RabbitListener(queues = RabbitMQConfig.INVENTORY_CONFIRM_QUEUE)
    @Retryable(
            value = {OptimisticLockingFailureException.class},
            maxAttempts = 5,
            backoff = @Backoff(delay = 2000, multiplier = 2)
    )
    public void handleConfirmStock( InventoryBatchRequest inventoryBatchEvent) {
        inventoryService.batchConfirmSaleWithIdempotency(inventoryBatchEvent);
    }
}
