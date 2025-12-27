package org.example.paymentservice.service.impl;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.messaging.PaymentProducer;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.PaymentStatus;
import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.service.PaymentService;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {
    private final PaymentRepository paymentRepository;
    private final PaymentProducer paymentProducer;
    @Override
    @Transactional
    public String processPayment(PaymentRequest request){
        // generate paymentNo
        String paymentNo = UUID.randomUUID().toString();

        // generate payment record
        Payment payment = new Payment();
        payment.setOrderId(request.getOrderId());
        payment.setPaymentNo(paymentNo);
        payment.setAmount(request.getAmount());
        payment.setStatus(PaymentStatus.PENDING);
        payment.setProvider("SIMULATED");

        paymentRepository.save(payment);
        // simulate payment
        boolean success = this.simulatePayment();
        if(success){
            payment.setStatus(PaymentStatus.PAID);
            payment.setProviderTxId(UUID.randomUUID().toString());
        } else {
            payment.setStatus(PaymentStatus.FAILED);
        }
        // update payment
        paymentRepository.save(payment);

        // sent notification (MQ event)
        paymentProducer.sendPaymentStatus(payment);

        return paymentNo;
    };
    private boolean simulatePayment(){
        // 90% successed
        return Math.random() < 0.9;
    };
}
