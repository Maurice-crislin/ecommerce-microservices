package org.example.paymentservice.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.paymentservice.messaging.RefundStatusProducer;
import org.example.paymentservice.model.Refund;
import org.common.payment.enums.RefundStatus;
import org.example.paymentservice.repository.RefundRepository;
import org.example.paymentservice.service.RefundAsyncProcessor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
public class RefundAsyncProcessorImpl implements RefundAsyncProcessor {
    private final RefundRepository refundRepository;
    private final RefundStatusProducer RefundStatusProducer;
    @Async
    @Transactional
    public void processRefundAsync(String refundNo) {
        Refund refund = refundRepository.findByRefundNo(refundNo).orElseThrow();

        simulateRefundAsync()
                .thenAccept(success -> {
                    if (success) {
                        refund.setStatus(RefundStatus.SUCCESS);
                        refund.setProviderRefundId(UUID.randomUUID().toString());
                        refundRepository.save(refund);
                        RefundStatusProducer.sendRefundSuccess(refund);
                    } else {
                        refund.setStatus(RefundStatus.FAILED);
                        refundRepository.save(refund);
                        RefundStatusProducer.sendRefundFailure(refund);
                    }

                })
                .exceptionally(e->{
                    refund.setStatus(RefundStatus.FAILED);
                    refundRepository.save(refund);
                    RefundStatusProducer.sendRefundFailure(refund);
                    return  null;
                });
    }

    @Async
    public CompletableFuture<Boolean> simulateRefundAsync(){
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 95% successed
        return CompletableFuture.completedFuture(Math.random() < 0.95);
    }
}
