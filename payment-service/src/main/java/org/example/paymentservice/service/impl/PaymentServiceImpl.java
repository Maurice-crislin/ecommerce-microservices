package org.example.paymentservice.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.service.PaymentBaseService;
import org.example.paymentservice.service.PaymentService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentBaseService paymentBaseService;

    @Override
    public Payment createPayment(PaymentRequest request) {
        try {
            // 正常创建并同步执行支付，返回最终状态的 Payment
            return paymentBaseService.doCreatePayment(request);
        } catch (DataIntegrityViolationException ex) {
            // 幂等处理：如果已存在相同订单的支付记录，直接返回该记录的最终状态
            return paymentBaseService.findExistingPayment(request.getOrderId());
        }
    }

    @Override
    public Payment queryPaymentByOrderId(Long orderId) {
        return paymentBaseService.findExistingPayment(orderId);
    }
}
