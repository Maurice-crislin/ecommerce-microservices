package org.example.paymentservice.service;


import org.example.paymentservice.model.Payment;

public interface PaymentAsyncProcessor {
    Payment processPaymentAsync(Payment payment);
}
