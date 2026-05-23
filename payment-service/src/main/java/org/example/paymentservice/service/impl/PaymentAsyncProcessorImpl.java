package org.example.paymentservice.service.impl;

import lombok.RequiredArgsConstructor;
import org.common.payment.enums.PaymentStatus;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.service.PaymentAsyncProcessor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentAsyncProcessorImpl implements PaymentAsyncProcessor {

    private final PaymentRepository paymentRepository;

    /**
     * 同步执行支付模拟（阻塞2秒，90%成功率）
     * 方法返回时，支付状态已更新为最终状态（PAID 或 FAILED）
     *
     * @return
     */
    @Override
    public Payment processPaymentAsync(Payment payment) {
        try {
            // 模拟耗时操作（2秒延迟）
            Thread.sleep(2000);

            boolean success = simulatePayment();
            if (success) {
                payment.setStatus(PaymentStatus.PAID);
                payment.setProviderTxId(UUID.randomUUID().toString());
            } else {
                payment.setStatus(PaymentStatus.FAILED);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 发生中断时标记为失败
            payment.setStatus(PaymentStatus.FAILED);
        } catch (Exception e) {
            // 其他异常也标记失败
            payment.setStatus(PaymentStatus.FAILED);
        }
        return payment;
    }

    private boolean simulatePayment() {
        // 90% 成功率
        return Math.random() < 0.9;
    }
}