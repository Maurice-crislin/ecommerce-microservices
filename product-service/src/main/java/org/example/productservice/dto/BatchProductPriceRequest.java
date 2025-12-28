package org.example.productservice.dto;

import lombok.Data;

import java.util.List;

@Data
public class BatchProductPriceRequest {
    private List<Long> productCodes;
}
