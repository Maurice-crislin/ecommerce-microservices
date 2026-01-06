package org.example.paymentservice.exception;

import lombok.Getter;

@Getter
public class RefundNotFoundException extends RuntimeException {
    private final String refundNo;
    public RefundNotFoundException(String message,String refundNo) {
        super(message);
        this.refundNo = refundNo;
    }
}
