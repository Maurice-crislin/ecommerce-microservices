package org.common.product.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Getter
public class InventoryBatchCheckResult {
    // totolly success or not
    @NotNull
    private boolean allValid;
    // item's productcode which is out of stock
    // only useful when success is false
    private List<Long> failedProductCodes;
}
