package org.example.paymentservice.service.impl;

import lombok.RequiredArgsConstructor;
import org.common.payment.enums.PaymentStatus;
import org.example.paymentservice.exception.RefundErrorException;
import org.example.paymentservice.exception.RefundNotFoundException;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.Refund;
import org.common.payment.enums.RefundStatus;
import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.repository.RefundRepository;
import org.example.paymentservice.service.RefundAsyncProcessor;
import org.example.paymentservice.service.RefundService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefundServiceImpl implements RefundService {
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final RefundAsyncProcessor refundAsyncProcessor;

    @Override
    public String processRefund(String paymentNo) {
        // check payment already paid
        Payment payment = paymentRepository.findPaymentByPaymentNo(paymentNo)
                .orElseThrow(() -> new RefundErrorException("Payment not found",null, RefundStatus.FAILED));

        if(!payment.getStatus().equals(PaymentStatus.PAID)){
            throw new RefundErrorException("Payment is not paid", null, RefundStatus.FAILED);
        }

        return this.createRefund(payment);
    }

    @Override
    public String createRefund(Payment payment) {
        try{
            Refund refund = new Refund();
            refund.setRefundNo(UUID.randomUUID().toString());
            refund.setPaymentNo(payment.getPaymentNo());
            refund.setOrderId(payment.getOrderId());
            refund.setAmount(payment.getAmount());
            refund.setStatus(RefundStatus.PROCESSING);
            refund.setProvider(payment.getProvider());

            refundRepository.save(refund);

            refundAsyncProcessor.processRefundAsync(refund.getRefundNo());

            return refund.getRefundNo();
        } catch (DataIntegrityViolationException e) {
            // already refund at before
            return refundRepository.findByPaymentNo(payment.getPaymentNo()).orElseThrow().getRefundNo();
        }

    }

    @Override
    public RefundStatus checkRefundStatus(String refundNo) {
        return refundRepository.findByPaymentNo(refundNo)
                .orElseThrow( () -> new RefundNotFoundException("refund not exists",refundNo))
                .getStatus();
    }
}
