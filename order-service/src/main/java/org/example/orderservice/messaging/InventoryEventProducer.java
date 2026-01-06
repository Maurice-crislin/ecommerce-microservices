package org.example.orderservice.messaging;

import lombok.RequiredArgsConstructor;
import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.message.InventoryEvent;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
public class InventoryEventProducer {
    // retry
    private final RabbitTemplate rabbitTemplate;
    public void sendBatchUnlockStockEvent(InventoryBatchRequest inventoryBatchRequest){
        rabbitTemplate.convertAndSend(RabbitMQConfig.INVENTORY_UNLOCK_EXCHANGE,RabbitMQConfig.INVENTORY_UNLOCK_ROUTING_KEY, inventoryBatchRequest);
    }
    public void sendBatchConfirmStockEvent(InventoryBatchRequest inventoryBatchRequest){
        rabbitTemplate.convertAndSend(RabbitMQConfig.INVENTORY_CONFIRM_EXCHANGE,RabbitMQConfig.INVENTORY_CONFIRM_ROUTING_KEY, inventoryBatchRequest);
    }
}
