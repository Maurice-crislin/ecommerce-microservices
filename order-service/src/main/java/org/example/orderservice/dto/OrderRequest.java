package org.example.orderservice.dto;

import lombok.Data;
import lombok.Getter;
import org.common.inventory.dto.StockRequest;

import java.util.List;

@Getter
@Data
public class OrderRequest {
    private String userId;
    private String idempotencyKey;
    private List<StockRequest> productRequests; // { productCode:quantity }
}
