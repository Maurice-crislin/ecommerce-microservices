package org.example.paymentservice.exception;

import lombok.Getter;
import org.common.payment.enums.RefundStatus;

@Getter
public class RefundErrorException extends RuntimeException{
    private final String refundNo;
    private final RefundStatus refundStatus;

    public RefundErrorException(String message,String refundNo, RefundStatus refundStatus) {
        super(message);
        this.refundNo = refundNo;
        this.refundStatus = refundStatus;
    }
}
