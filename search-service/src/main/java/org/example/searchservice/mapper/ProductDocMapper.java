package org.example.searchservice.mapper;

import org.common.mq.events.ProductCreatedEvent;
import org.common.mq.events.ProductUpdatedEvent;
import org.example.searchservice.document.ProductDoc;

public class ProductDocMapper {
    public static ProductDoc mapToDoc(ProductCreatedEvent event) {
        if (event == null) {
            return null;
        }

        return new ProductDoc(
                event.getProductCode(),
                event.getProductName(),
                event.getPrice()!=null ? event.getPrice().doubleValue():0,
                event.getStatus(),
                event.getDescription(),
                event.getBrand(),
                event.getCategoryCode()
        );
    }
    public static ProductDoc mapToDoc(ProductUpdatedEvent event) {
        if (event == null) {
            return null;
        }

        return new ProductDoc(
                event.getProductCode(),
                event.getProductName(),
                event.getPrice()!=null ? event.getPrice().doubleValue():0,
                event.getStatus(),
                event.getDescription(),
                event.getBrand(),
                event.getCategoryCode()
        );
    }
}
