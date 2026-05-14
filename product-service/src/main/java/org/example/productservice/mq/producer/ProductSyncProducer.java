package org.example.productservice.mq.producer;

import lombok.RequiredArgsConstructor;
import org.common.mq.constants.ProductMQConstants;
import org.common.mq.events.ProductCreatedEvent;
import org.common.mq.events.ProductDeletedEvent;
import org.common.mq.events.ProductUpdatedEvent;
import org.example.productservice.entity.Product;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductSyncProducer {
    private final RabbitTemplate rabbitTemplate;

    public void publishCreateProductEvent(ProductCreatedEvent event) {
        rabbitTemplate.convertAndSend(
                ProductMQConstants.EXCHANGE_NAME,
                ProductMQConstants.CREATED_ROUTE_KEY,
                event);
    }
    public void publishUpdateProductEvent(ProductUpdatedEvent event) {
        rabbitTemplate.convertAndSend(
                ProductMQConstants.EXCHANGE_NAME,
                ProductMQConstants.UPDATED_ROUTE_KEY,
                event
        );
    }
    public void publishDeleteProductEvent(ProductDeletedEvent event) {
        rabbitTemplate.convertAndSend(
                ProductMQConstants.EXCHANGE_NAME,
                ProductMQConstants.DELETED_ROUTE_KEY,
                event
        );
    }
}
