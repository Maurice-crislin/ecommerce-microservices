package org.example.paymentservice.controller.advice;

import org.common.payment.dto.RefundResponse;
import org.example.paymentservice.dto.PaymentResponse;
import org.example.paymentservice.exception.DuplicatePaymentException;
import org.example.paymentservice.exception.RefundErrorException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class PaymentExceptionHandler {

    @ExceptionHandler(DuplicatePaymentException.class)
    public ResponseEntity<PaymentResponse> handleDuplicatePaymentException(DuplicatePaymentException e) {
        return ResponseEntity.accepted()
                .body(new PaymentResponse(
                        e.getPaymentNo(),
                        e.getPaymentStatus(),
                        e.getMessage()
                ));
    }
    @ExceptionHandler(RefundErrorException.class)
    public ResponseEntity<RefundResponse> handleRefundErrorException(RefundErrorException e) {

        HttpStatus status = HttpStatus.BAD_REQUEST; // 400

        return ResponseEntity.status(status).body(
                new RefundResponse(
                        e.getRefundNo(),
                        e.getRefundStatus(),
                        e.getMessage()
                )
        );
    }
}

