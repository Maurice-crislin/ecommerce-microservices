package org.common.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.common.payment.enums.RefundStatus;

@Data
@AllArgsConstructor
public class RefundResponse {
    private String refundNo;
    private RefundStatus status;
    private String message;
}
