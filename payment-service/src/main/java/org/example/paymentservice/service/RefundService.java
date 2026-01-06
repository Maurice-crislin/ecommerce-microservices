package org.example.paymentservice.service;

import org.common.payment.enums.RefundStatus;
import org.example.paymentservice.model.Payment;


public interface RefundService {
    String processRefund(String paymentNo);
    String createRefund(Payment payment);
    RefundStatus checkRefundStatus(String refundNo);
}
