package org.common.product.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BatchProductPriceResponse {
    private boolean allProductsOrderable;
    private List<ProductPriceResponse> products;
    private List<Long> missingProductCodes;
}
