package org.example.inventoryservice;

import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.dto.StockRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MQ 基础连通性测试
 *
 * 使用独立的测试队列，不干扰 @RabbitListener 使用的生产队列。
 * RabbitAdmin 会自动在 RabbitMQ 中创建测试队列。
 */
@SpringBootTest
public class MQConnectivityTest {

    private static final String TEST_QUEUE = "test.connectivity.queue";

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @BeforeEach
    void setup() {
        // 声明测试队列（RabbitAdmin 会在 RabbitMQ 中创建它）
        rabbitAdmin.declareQueue(new Queue(TEST_QUEUE, false, false, true));
        // 清空测试队列（确保没有旧消息）
        rabbitTemplate.execute(channel -> {
            channel.queuePurge(TEST_QUEUE);
            return null;
        });
    }

    @AfterEach
    void teardown() {
        // 删除测试队列
        rabbitAdmin.deleteQueue(TEST_QUEUE);
    }

    /**
     * 测试1: 最基本的原始字节收发 —— 验证 RabbitMQ 连接正常
     */
    @Test
    @DisplayName("MQ基础测试1: 原始消息队列收发")
    void testRawMessageSendReceive() {
        rabbitTemplate.send(TEST_QUEUE,
                MessageBuilder.withBody("hello-test".getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN)
                        .build()
        );

        Message received = rabbitTemplate.receive(TEST_QUEUE, 3000);
        assertNotNull(received, "应该能在3秒内收到消息");
        assertEquals("hello-test", new String(received.getBody(), StandardCharsets.UTF_8));
    }

    /**
     * 测试2: 验证 Jackson 序列化/反序列化 InventoryBatchRequest
     *
     * RabbitMQConfig 中 trustedPackages 已配置为 "org.common.*"，
     * 所以 receiveAndConvert 可以正确反序列化来自 org.common 包的对象。
     */
    @Test
    @DisplayName("MQ基础测试2: Jackson序列化/反序列化")
    void testJacksonSerialization() {
        InventoryBatchRequest request = new InventoryBatchRequest();
        request.setOrderId(12345L);
        request.setStockRequestList(List.of(
                new StockRequest(111L, 10),
                new StockRequest(222L, 20)
        ));

        // convertAndSend 触发 Jackson 序列化
        rabbitTemplate.convertAndSend(TEST_QUEUE, request);

        // receiveAndConvert 触发 Jackson 反序列化
        InventoryBatchRequest received = (InventoryBatchRequest)
                rabbitTemplate.receiveAndConvert(TEST_QUEUE, 5000);

        assertNotNull(received, "应收到反序列化后的 InventoryBatchRequest");
        assertEquals(Long.valueOf(12345L), received.getOrderId(), "orderId 应正确");
        assertNotNull(received.getStockRequestList(), "stockRequestList 不应为空");
        assertEquals(2, received.getStockRequestList().size(), "应有2个 StockRequest");

        assertEquals(Long.valueOf(111L), received.getStockRequestList().get(0).getProductCode());
        assertEquals(Integer.valueOf(10), received.getStockRequestList().get(0).getQuantity());
        assertEquals(Long.valueOf(222L), received.getStockRequestList().get(1).getProductCode());
        assertEquals(Integer.valueOf(20), received.getStockRequestList().get(1).getQuantity());
    }
}