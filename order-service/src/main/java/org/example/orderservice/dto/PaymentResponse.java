package org.example.orderservice.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import org.example.orderservice.model.PaymentStatus;

@Data
@AllArgsConstructor
public class PaymentResponse {
    private String paymentNo;
    private PaymentStatus status;
    private String message;
}
