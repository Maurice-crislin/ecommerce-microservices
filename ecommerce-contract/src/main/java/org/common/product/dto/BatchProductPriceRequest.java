package org.common.product.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class BatchProductPriceRequest {
    private List<Long> productCodes;
}


