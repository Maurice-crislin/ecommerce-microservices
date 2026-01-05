package org.example.inventoryservice;

import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.dto.StockRequest;
import org.example.inventoryservice.messaging.InventoryEventListener;
import org.example.inventoryservice.messaging.RabbitMQConfig;
import org.example.inventoryservice.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.test.context.SpringRabbitTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.util.List;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@SpringRabbitTest // ✅ 开启 in-memory Rabbit 支持

public class InventoryEventViaMqTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @SpyBean
    private InventoryService inventoryService;

    // 可以直接调用 listener 也可以通过 RabbitTemplate 测试消息发送
    @Autowired
    private InventoryEventListener inventoryEventListener;

    @Test
    void testHandleUnlockStock_viaRabbitTemplate() throws InterruptedException {
        InventoryBatchRequest event = new InventoryBatchRequest(1L,
                List.of(new StockRequest(1001L, 2)));

        // 发送消息到 listener 绑定的 exchange + routing key
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_UNLOCK_EXCHANGE,
                RabbitMQConfig.INVENTORY_UNLOCK_ROUTING_KEY,
                event
        );

        // 等待 listener 异步处理
        Thread.sleep(500);

        verify(inventoryService, times(1))
                .batchUnlockStockWithIdempotency(event);
    }

    @Test
    void testHandleConfirmStock_viaRabbitTemplate() throws InterruptedException {
        InventoryBatchRequest event = new InventoryBatchRequest(2L,
                List.of(new StockRequest(2001L, 1)));

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_CONFIRM_EXCHANGE,
                RabbitMQConfig.INVENTORY_CONFIRM_ROUTING_KEY,
                event
        );

        Thread.sleep(500);

        verify(inventoryService, times(1))
                .batchConfirmSaleWithIdempotency(event);
    }
}
