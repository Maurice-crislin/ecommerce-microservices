package org.common.mq.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.common.product.enums.CategoryCode;
import org.common.product.enums.ProductStatus;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ProductCreatedEvent {

    private Long productCode;

    private String productName;

    private BigDecimal price;

    private ProductStatus status;

    private String description;

    private String brand;

    private CategoryCode categoryCode;
}
