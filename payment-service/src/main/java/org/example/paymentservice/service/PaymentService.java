package org.example.paymentservice.service;

import org.example.paymentservice.model.Payment;
import org.example.paymentservice.dto.PaymentRequest;

public interface PaymentService {
    /**
     * 创建并执行支付（同步等待结果）
     */
    Payment createPayment(PaymentRequest request);
}