package org.common.inventory.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryBatchRequest {
    private Long orderId;
    @NotEmpty(message = "Stock request list cannot be empty")
    private List<StockRequest> stockRequestList; // StockRequest { productCode, quantity }
}
