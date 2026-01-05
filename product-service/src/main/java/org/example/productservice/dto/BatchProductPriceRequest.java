package org.example.productservice.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class BatchProductPriceRequest {
    @NotNull
    private List<Long> productCodes;
}
