package org.example.paymentservice.exception;

import lombok.Getter;
import org.common.payment.enums.PaymentStatus;

@Getter
public class DuplicatePaymentException extends RuntimeException {
    private final String paymentNo;
    private final PaymentStatus paymentStatus;
    public DuplicatePaymentException(String paymentNo, PaymentStatus paymentStatus) {
        super("Duplicate payment request for payment: " + paymentNo);
        this.paymentNo = paymentNo;
        this.paymentStatus = paymentStatus;
    }
}
