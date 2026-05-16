package org.example.productservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.common.product.enums.CategoryCode;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ProductUpdateRequest {
    // --- Product 表字段 ---
    private BigDecimal price;

    // --- ProductDetail 表字段
    private String brand;
    private String description;
    private CategoryCode categoryCode;
}
