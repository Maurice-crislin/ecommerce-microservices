package org.example.paymentservice.service;

import org.example.paymentservice.dto.PaymentRequest;

public interface PaymentService {
    String processPayment(PaymentRequest request);
}
