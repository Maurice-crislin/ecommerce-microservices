package org.example.productservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.common.product.enums.ProductStatus;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ProductPriceResponse {
    private Long productCode;
    private BigDecimal price;
    private ProductStatus status;
}
