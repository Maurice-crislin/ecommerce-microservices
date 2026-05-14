package org.example.productservice.mapper;

import org.common.mq.events.ProductCreatedEvent;
import org.common.mq.events.ProductDeletedEvent;
import org.common.mq.events.ProductUpdatedEvent;
import org.example.productservice.entity.Product;
import org.example.productservice.entity.ProductDetail;
import org.springframework.stereotype.Component;

@Component
public class ProductEventMapper {
    public ProductCreatedEvent toCreateEvent(Product product) {
        ProductDetail detail = product.getProductDetail();

        return new ProductCreatedEvent(
                product.getProductCode(),
                product.getProductName(),
                product.getPrice(),
                product.getStatus(),
                detail != null ? detail.getDescription() : null, // 从关联表取值
                detail != null ? detail.getBrand() : null,       // 从关联表取值
                detail != null ? detail.getCategoryCode() : null // 从关联表取值
        );
    }
    public ProductUpdatedEvent toUpdatedEvent(Product product){
        ProductDetail detail = product.getProductDetail();

        return new ProductUpdatedEvent(
                product.getProductCode(),
                product.getProductName(),
                product.getPrice(),
                product.getStatus(),
                detail != null ? detail.getDescription() : null,
                detail != null ? detail.getBrand() : null,
                detail != null ? detail.getCategoryCode() : null
        );
    }
    public ProductDeletedEvent toDeletedEvent(Product product){
        return new ProductDeletedEvent(product.getProductCode());
    }
}




