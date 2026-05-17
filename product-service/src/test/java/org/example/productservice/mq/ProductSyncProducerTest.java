package org.example.productservice.mq;

import org.common.mq.constants.ProductMQConstants;
import org.common.mq.events.ProductCreatedEvent;
import org.common.mq.events.ProductDeletedEvent;
import org.common.mq.events.ProductUpdatedEvent;
import org.common.product.enums.CategoryCode;
import org.common.product.enums.ProductStatus;
import org.example.productservice.mq.producer.ProductSyncProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

/**
 * ProductSyncProducer 单元测试
 * 
 * 验证 ProductSyncProducer 正确调用 RabbitTemplate 发送消息到正确的 Exchange 和 Routing Key
 */
@ExtendWith(MockitoExtension.class)
class ProductSyncProducerTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Captor
    private ArgumentCaptor<Object> messageCaptor;

    @Captor
    private ArgumentCaptor<String> exchangeCaptor;

    @Captor
    private ArgumentCaptor<String> routingKeyCaptor;

    private ProductSyncProducer producer;

    @BeforeEach
    void setUp() {
        producer = new ProductSyncProducer(rabbitTemplate);
    }

    // ======================================================================
    // 场景 1: 发布创建商品事件
    // ======================================================================
    @Test
    @DisplayName("publishCreateProductEvent 应发送消息到正确的 Exchange 和 Routing Key")
    void testPublishCreateProductEvent() {
        // Given
        ProductCreatedEvent event = new ProductCreatedEvent(
                1001L,
                "iPhone 15 Pro",
                BigDecimal.valueOf(9999.00),
                ProductStatus.ACTIVE,
                "Apple flagship phone",
                "Apple",
                CategoryCode.SMARTPHONE
        );

        // When
        producer.publishCreateProductEvent(event);

        // Then
        verify(rabbitTemplate).convertAndSend(
                exchangeCaptor.capture(),
                routingKeyCaptor.capture(),
                messageCaptor.capture()
        );

        assertEquals(ProductMQConstants.EXCHANGE_NAME, exchangeCaptor.getValue());
        assertEquals(ProductMQConstants.CREATED_ROUTE_KEY, routingKeyCaptor.getValue());
        assertNotNull(messageCaptor.getValue());
        
        // 验证消息内容
        ProductCreatedEvent sentEvent = (ProductCreatedEvent) messageCaptor.getValue();
        assertEquals(1001L, sentEvent.getProductCode());
        assertEquals("iPhone 15 Pro", sentEvent.getProductName());
        assertEquals(9999.00, sentEvent.getPrice().doubleValue());
        assertEquals(ProductStatus.ACTIVE, sentEvent.getStatus());
        assertEquals("Apple flagship phone", sentEvent.getDescription());
        assertEquals("Apple", sentEvent.getBrand());
        assertEquals(CategoryCode.SMARTPHONE, sentEvent.getCategoryCode());
    }

    // ======================================================================
    // 场景 2: 发布更新商品事件
    // ======================================================================
    @Test
    @DisplayName("publishUpdateProductEvent 应发送消息到正确的 Exchange 和 Routing Key")
    void testPublishUpdateProductEvent() {
        // Given
        ProductUpdatedEvent event = new ProductUpdatedEvent(
                2001L,
                "MacBook Pro M3",
                BigDecimal.valueOf(12999.00),
                ProductStatus.ACTIVE,
                "Updated description",
                "Apple",
                CategoryCode.LAPTOP
        );

        // When
        producer.publishUpdateProductEvent(event);

        // Then
        verify(rabbitTemplate).convertAndSend(
                exchangeCaptor.capture(),
                routingKeyCaptor.capture(),
                messageCaptor.capture()
        );

        assertEquals(ProductMQConstants.EXCHANGE_NAME, exchangeCaptor.getValue());
        assertEquals(ProductMQConstants.UPDATED_ROUTE_KEY, routingKeyCaptor.getValue());
        
        ProductUpdatedEvent sentEvent = (ProductUpdatedEvent) messageCaptor.getValue();
        assertEquals(2001L, sentEvent.getProductCode());
        assertEquals("MacBook Pro M3", sentEvent.getProductName());
    }

    // ======================================================================
    // 场景 3: 发布删除商品事件
    // ======================================================================
    @Test
    @DisplayName("publishDeleteProductEvent 应发送消息到正确的 Exchange 和 Routing Key")
    void testPublishDeleteProductEvent() {
        // Given
        ProductDeletedEvent event = new ProductDeletedEvent(3001L);

        // When
        producer.publishDeleteProductEvent(event);

        // Then
        verify(rabbitTemplate).convertAndSend(
                exchangeCaptor.capture(),
                routingKeyCaptor.capture(),
                messageCaptor.capture()
        );

        assertEquals(ProductMQConstants.EXCHANGE_NAME, exchangeCaptor.getValue());
        assertEquals(ProductMQConstants.DELETED_ROUTE_KEY, routingKeyCaptor.getValue());
        
        ProductDeletedEvent sentEvent = (ProductDeletedEvent) messageCaptor.getValue();
        assertEquals(3001L, sentEvent.getProductCode());
    }
}
