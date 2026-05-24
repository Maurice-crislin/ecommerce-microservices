package org.example.orderservice.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.common.order.enums.OrderIdeStatus;

@Getter
@Setter
@AllArgsConstructor
public class IdempotencyRecord {
    @NotNull
    private Long orderId;
    @NotNull
    private OrderIdeStatus status;
}
