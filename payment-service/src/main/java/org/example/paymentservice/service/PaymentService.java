package org.example.paymentservice.service;

import org.example.paymentservice.model.Payment;
import org.example.paymentservice.dto.PaymentRequest;

public interface PaymentService {
    /**
     * 创建并执行支付（同步等待结果）
     */
    Payment createPayment(PaymentRequest request);

    /**
     * 只读查询指定订单的支付状态（不触发支付流程）
     * 用于补偿查询、幂等校验等场景
     */
    Payment queryPaymentByOrderId(Long orderId);
}
