package org.example.orderservice.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.common.order.enums.OrderIdeStatus;

@Getter
@Setter
@AllArgsConstructor
public class IdempotencyRecord {
    private Long orderId;
    private OrderIdeStatus status;
}
