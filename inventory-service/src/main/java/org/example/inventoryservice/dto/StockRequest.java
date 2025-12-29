package org.example.inventoryservice.dto;

import lombok.Getter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Getter
public class StockRequest {
    @NotNull
    private Long productCode;
    @Min(1)
    @NotNull
    private Integer quantity;
}
