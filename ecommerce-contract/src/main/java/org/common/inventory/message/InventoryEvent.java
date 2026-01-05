package org.common.inventory.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryEvent {
    private Long productCode;
    private Integer quantity;
    private Long orderId;
}
