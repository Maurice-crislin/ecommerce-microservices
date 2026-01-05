package org.common.payment.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import org.common.payment.enums.PaymentStatus;

@Data
@AllArgsConstructor
public class PaymentResponse {
    private String paymentNo;
    private PaymentStatus status;
    private String message;
}
