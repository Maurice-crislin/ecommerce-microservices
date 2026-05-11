package org.example.productservice.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductCreateRequest {
    private String productName;
    private BigDecimal price;
}
