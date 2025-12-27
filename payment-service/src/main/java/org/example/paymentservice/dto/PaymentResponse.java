package org.example.paymentservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.example.paymentservice.model.PaymentStatus;

@Data
@AllArgsConstructor
public class PaymentResponse {
    private String paymentNo;
    private PaymentStatus status;
    private String message;
}
