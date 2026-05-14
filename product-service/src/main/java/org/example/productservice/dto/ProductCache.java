package org.example.productservice.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.common.product.enums.ProductStatus;


import java.math.BigDecimal;


@Getter
@Setter
@AllArgsConstructor
public class ProductCache {
    private Long productCode;
    private String productName;
    private BigDecimal price;
    private ProductStatus status;
}
