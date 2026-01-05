package org.example.paymentservice;

import org.example.paymentservice.controller.PaymentController;

import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.dto.PaymentResponse;
import org.example.paymentservice.messaging.RabbitMQConfig;

import org.example.paymentservice.repository.PaymentRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PaymentConcurrencyTest {

    @Autowired
    private PaymentController paymentController;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private static final int THREAD_COUNT = 10;

    @BeforeEach
    void clean() {
        paymentRepository.deleteAll();
        paymentRepository.flush();
        while (rabbitTemplate.receive(RabbitMQConfig.PAYMENT_FAILED_STATUS_QUEUE) != null) {}
        while (rabbitTemplate.receive(RabbitMQConfig.PAYMENT_SUCCESS_STATUS_QUEUE) != null) {}
    }

    @Test
    void concurrentPayment_shouldBeIdempotent() throws Exception {

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<ResponseEntity<PaymentResponse>>> futures = new ArrayList<>();

        for (int i = 0; i < THREAD_COUNT; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();

                PaymentRequest request = new PaymentRequest();
                setField(request, "orderId", 999L);
                setField(request, "userId", "user1");
                setField(request, "amount", new BigDecimal("100"));

                return paymentController.payment(request);
            }));
        }

        ready.await();
        start.countDown();

        Set<String> paymentNos = new HashSet<>();

        for (Future<ResponseEntity<PaymentResponse>> f : futures) {
            ResponseEntity<PaymentResponse> resp = f.get();
            assertNotNull(resp.getBody());
            paymentNos.add(resp.getBody().getPaymentNo());
        }

        // ✅ 所有请求返回的是同一个 paymentNo
        assertEquals(1, paymentNos.size());

        // ✅ DB 里只有一条 payment
        assertEquals(1, paymentRepository.count());

        executor.shutdown();
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}

