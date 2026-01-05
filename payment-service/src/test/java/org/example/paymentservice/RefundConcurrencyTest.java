package org.example.paymentservice;

import org.common.payment.enums.PaymentStatus;
import org.example.paymentservice.controller.PaymentController;

import org.example.paymentservice.model.Payment;

import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.repository.RefundRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;


import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RefundConcurrencyTest {

    @Autowired
    private PaymentController paymentController;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private RefundRepository refundRepository;

    private static final int THREAD_COUNT = 220;

    @BeforeEach
    void clean() {
        refundRepository.deleteAll();
        paymentRepository.deleteAll();
        refundRepository.flush();
        paymentRepository.flush();
    }

    @Test
    void concurrentRefund_shouldBeIdempotent() throws Exception {

        // 1️⃣ prepare paid payment
        Payment payment = new Payment();
        payment.setOrderId(3001L);
        payment.setPaymentNo("PAY-" + System.currentTimeMillis());
        payment.setAmount(new BigDecimal("200"));
        payment.setStatus(PaymentStatus.PAID);
        payment.setProvider("SIMULATED");
        paymentRepository.saveAndFlush(payment);

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < THREAD_COUNT; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return paymentController
                        .refund(payment.getPaymentNo())
                        .getBody()
                        .getRefundNo();
            }));
        }

        ready.await();
        start.countDown();

        Set<String> refundNos = new HashSet<>();

        for (Future<String> f : futures) {
            refundNos.add(f.get());
        }


        // ✅ 所有并发请求，拿到的是同一个 refundNo
        assertEquals(1, refundNos.size());

        // ✅ DB 中只存在一条 refund
        assertEquals(1, refundRepository.count());

        executor.shutdown();
    }
}

