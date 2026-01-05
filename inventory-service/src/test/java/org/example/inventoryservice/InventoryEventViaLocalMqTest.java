package org.example.inventoryservice;

import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.dto.StockRequest;

import org.example.inventoryservice.service.InventoryService;
import org.example.inventoryservice.messaging.RabbitMQConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import static org.awaitility.Awaitility.await;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于本地 RabbitMQ 的集成测试
 * ✅ 消息真正走 RabbitMQ
 * ✅ 使用 SpyBean 验证 listener 调用 InventoryService
 * ✅ Awaitility 等待异步消费完成，避免 zero interactions
 */
@SpringBootTest
public class InventoryEventViaLocalMqTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @MockBean
    private InventoryService inventoryService;

    @Test
    void testUnlockStock_viaLocalRabbitMQ() {
        InventoryBatchRequest event = new InventoryBatchRequest(
                1L,
                List.of(new StockRequest(1001L, 2))
        );

        // 发送消息到 listener 绑定的 exchange + routing key
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_UNLOCK_EXCHANGE,
                RabbitMQConfig.INVENTORY_UNLOCK_ROUTING_KEY,
                event
        );

        // 等待异步消费完成（最多等待 5 秒，每 100ms 重试）
        await().atMost(5, TimeUnit.SECONDS).pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        verify(inventoryService, times(1))
                                .batchUnlockStockWithIdempotency(event)
                );
    }

    @Test
    void testConfirmStock_viaLocalRabbitMQ() {
        InventoryBatchRequest event = new InventoryBatchRequest(
                2L,
                List.of(new StockRequest(2001L, 1))
        );

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_CONFIRM_EXCHANGE,
                RabbitMQConfig.INVENTORY_CONFIRM_ROUTING_KEY,
                event
        );

        await().atMost(5, TimeUnit.SECONDS).pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        verify(inventoryService, times(1))
                                .batchConfirmSaleWithIdempotency(event)
                );
    }
}
