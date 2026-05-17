package org.example.searchservice.mq.consumer;

import lombok.RequiredArgsConstructor;
import org.common.mq.events.ProductCreatedEvent;
import org.common.mq.events.ProductDeletedEvent;
import org.common.mq.events.ProductUpdatedEvent;
import org.example.searchservice.document.ProductDoc;
import org.example.searchservice.mapper.ProductDocMapper;
import org.example.searchservice.repository.ProductRepository;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductSyncConsumer {
    private final ProductRepository productRepository;


    @RabbitListener(
            queues="product-created"
    )
    public void handleCreated(ProductCreatedEvent event) {
        ProductDoc doc = ProductDocMapper.mapToDoc(event);
        productRepository.save(doc);
    }

    @RabbitListener(
            queues="product-updated"
    )
    public void handleUpdated(ProductUpdatedEvent event) {
        ProductDoc doc = ProductDocMapper.mapToDoc(event);
        productRepository.save(doc);
    }

    @RabbitListener(
            queues="product-deleted"
    )
    public void handleDeleted(ProductDeletedEvent event) {
        productRepository.deleteByProductCode(event.getProductCode());
    }

}