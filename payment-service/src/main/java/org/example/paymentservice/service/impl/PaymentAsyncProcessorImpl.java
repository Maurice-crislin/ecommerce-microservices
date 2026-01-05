package org.example.paymentservice.service.impl;

import lombok.RequiredArgsConstructor;
import org.common.payment.enums.PaymentStatus;
import org.example.paymentservice.messaging.PaymentProducer;
import org.example.paymentservice.model.Payment;

import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.service.PaymentAsyncProcessor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentAsyncProcessorImpl implements PaymentAsyncProcessor {
    private final PaymentRepository paymentRepository;
    private final PaymentProducer paymentProducer;
    @Async
    @Transactional
    public void processPaymentAsync(String paymentNo) {
        try {
            Payment payment = paymentRepository
                    .findPaymentByPaymentNo(paymentNo)
                    .orElseThrow();

            Thread.sleep(2000);
            // simulate payment
            boolean success = simulatePayment();
            if(success){
                payment.setStatus(PaymentStatus.PAID);
                payment.setProviderTxId(UUID.randomUUID().toString());

                // update payment
                paymentRepository.save(payment);
                // sent notification (MQ event)
                paymentProducer.sendPaymentStatusSuccess(payment);
            } else {
                payment.setStatus(PaymentStatus.FAILED);

                paymentRepository.save(payment);
                paymentProducer.sendPaymentStatusFailed(payment);
            }
        } catch (Exception e) {

            paymentRepository.findPaymentByPaymentNo(paymentNo).ifPresent(payment -> {
                payment.setStatus(PaymentStatus.FAILED);
                paymentRepository.save(payment);
                paymentProducer.sendPaymentStatusFailed(payment);
            });
        }

    }

    private boolean simulatePayment(){
        // 90% successed
        return Math.random() < 0.9;
    };
}
