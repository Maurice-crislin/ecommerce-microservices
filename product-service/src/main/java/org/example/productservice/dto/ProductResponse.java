package org.example.productservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.common.product.enums.CategoryCode;
import org.common.product.enums.ProductStatus;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ProductResponse {
    // --- Product 表字段 ---
    private Long productCode;
    private String productName;
    private BigDecimal price;
    private ProductStatus status;

    // --- ProductDetail 表字段 (直接嵌入) ---
    private String brand;
    private String description;
    private CategoryCode categoryCode;

}
