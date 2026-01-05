package org.example.paymentservice.service;

import lombok.RequiredArgsConstructor;
import org.common.payment.enums.PaymentStatus;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.model.Payment;


import org.example.paymentservice.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentBaseService {
    private final PaymentRepository paymentRepository;
    private final PaymentAsyncProcessor paymentAsyncProcessor;

    @Transactional
    public String doCreatePayment(PaymentRequest request){
        // 1. create and save payment

        // generate payment record
        Payment payment = new Payment();

        payment.setOrderId(request.getOrderId());
        payment.setPaymentNo(UUID.randomUUID().toString());
        payment.setAmount(request.getAmount());
        payment.setStatus(PaymentStatus.PROCESSING);
        payment.setProvider("SIMULATED");

        paymentRepository.save(payment);

        // 2. open processPayment task
        paymentAsyncProcessor.processPaymentAsync(payment.getPaymentNo());

        return payment.getPaymentNo();
    };

    @Transactional(readOnly = true)
    public String findExistingPaymentNo(Long orderId) {
        return paymentRepository.findPaymentByOrderId(orderId)
                .orElseThrow()
                .getPaymentNo();
    };
}
