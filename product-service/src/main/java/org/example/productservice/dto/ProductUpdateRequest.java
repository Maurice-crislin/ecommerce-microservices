package org.example.productservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class ProductUpdateRequest {
    private BigDecimal price;
}
