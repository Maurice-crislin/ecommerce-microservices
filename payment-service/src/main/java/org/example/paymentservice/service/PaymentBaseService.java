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

    /**
     * 创建并同步执行支付，等待最终结果后返回 Payment 对象
     */
    @Transactional
    public Payment doCreatePayment(PaymentRequest request) {
        // 1. 创建初始状态为 Pending 的支付记录
        Payment payment = new Payment();
        payment.setOrderId(request.getOrderId());
        payment.setPaymentNo(UUID.randomUUID().toString());
        payment.setAmount(request.getAmount());
        payment.setProvider("SIMULATED");

        paymentRepository.saveAndFlush(payment);

        // 2. 同步调用支付处理（会阻塞 2 秒，并更新 payment 状态为 PAID 或 FAILED）
        // 返回的是同一个 payment 对象引用
        payment = paymentAsyncProcessor.processPaymentAsync(payment);


        // 3. 重新从数据库获取最终状态的 Payment 并返回
        return payment;
    }

    @Transactional(readOnly = true)
    public Payment findExistingPayment(Long orderId) {
        // 注意：原方法名为 findExistingPaymentNo，现改为返回完整 Payment 对象
        return paymentRepository.findPaymentByOrderId(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found for orderId: " + orderId));
    }
}