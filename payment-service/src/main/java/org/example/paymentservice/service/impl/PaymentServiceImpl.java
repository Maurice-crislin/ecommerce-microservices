package org.example.paymentservice.service.impl;

import org.example.paymentservice.service.PaymentBaseService;
import lombok.RequiredArgsConstructor;
import org.example.paymentservice.dto.PaymentRequest;

import org.example.paymentservice.service.PaymentService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {
    private final PaymentBaseService paymentBaseService;
    @Override
    public String createPayment(PaymentRequest request){
        // attention: (don't flush the Session after an exception occurs)
        try {
            return paymentBaseService.doCreatePayment(request);
        } catch (DataIntegrityViolationException ex) {
            // idempotence
            return paymentBaseService.findExistingPaymentNo(request.getOrderId());
        }
    }
}
