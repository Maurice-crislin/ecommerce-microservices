package org.example.inventoryservice.messaging;

import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.dto.StockRequest;
import org.example.inventoryservice.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.OptimisticLockingFailureException;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.mockito.Mockito.*;

@SpringBootTest
@Testcontainers
public class InventoryEventListenerIntegrationTest {

    @Container
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:3.12-management-alpine")
            .withExposedPorts(5672, 15672);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @MockBean
    private InventoryService inventoryService;

    @BeforeEach
    void setup() {
        // 将 RabbitTemplate 指向 Testcontainers 的 RabbitMQ
        rabbitTemplate.setExchange(RabbitMQConfig.INVENTORY_UNLOCK_EXCHANGE);
    }

    @Test
    void testNormalConsumeAndIdempotency() throws InterruptedException {
        InventoryBatchRequest event = new InventoryBatchRequest(1L,
                List.of(new StockRequest(1001L, 2)));

        // 第一次发送消息
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_UNLOCK_EXCHANGE,
                RabbitMQConfig.INVENTORY_UNLOCK_ROUTING_KEY,
                event
        );

        Thread.sleep(500); // 等待消费

        verify(inventoryService, times(1))
                .batchUnlockStockWithIdempotency(event);

        // 第二次发送相同消息（模拟 MQ 重试 / 幂等性）
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_UNLOCK_EXCHANGE,
                RabbitMQConfig.INVENTORY_UNLOCK_ROUTING_KEY,
                event
        );

        Thread.sleep(500);

        // 验证幂等逻辑生效，service 只执行一次
        verify(inventoryService, times(1))
                .batchUnlockStockWithIdempotency(event);
    }

    @Test
    void testRetryOnOptimisticLockingFailure() throws InterruptedException {
        InventoryBatchRequest event = new InventoryBatchRequest(2L,
                List.of(new StockRequest(2001L, 1)));

        // 模拟 service 抛 OptimisticLockingFailureException
        doThrow(new OptimisticLockingFailureException("Simulate optimistic lock"))
                .when(inventoryService)
                .batchConfirmSaleWithIdempotency(event);

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.INVENTORY_CONFIRM_EXCHANGE,
                RabbitMQConfig.INVENTORY_CONFIRM_ROUTING_KEY,
                event
        );

        Thread.sleep(12000); // 等待 listener 重试 (默认 5 次，delay=2s, multiplier=2)

        // 验证 listener 重试了 5 次
        verify(inventoryService, times(5))
                .batchConfirmSaleWithIdempotency(event);
    }
}
