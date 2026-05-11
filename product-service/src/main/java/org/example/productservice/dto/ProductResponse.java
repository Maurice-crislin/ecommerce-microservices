package org.example.productservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.example.productservice.enums.ProductStatus;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class ProductResponse {
    private Long productCode;
    private String productName;
    private BigDecimal price;
    private ProductStatus status;
}
