package org.example.paymentservice;

import org.example.paymentservice.controller.PaymentController;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.dto.PaymentResponse;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.PaymentStatus;
import org.example.paymentservice.messaging.PaymentStatusMessage;
import org.example.paymentservice.repository.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class PaymentServiceIntegrationTest {

    @Autowired
    private PaymentController paymentController;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private RabbitTemplate testRabbitTemplate;

    // MQ queue name
    private final String PAYMENT_STATUS_QUEUE = "payment_status_queue";

    @BeforeEach
    public void setup() {
        // RabbitTemplate converter
        testRabbitTemplate = new RabbitTemplate(rabbitTemplate.getConnectionFactory());
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTrustedPackages("org.example.paymentservice.messaging");
        converter.setJavaTypeMapper(typeMapper);
        testRabbitTemplate.setMessageConverter(converter);
        // clear queue
        while (testRabbitTemplate.receive(PAYMENT_STATUS_QUEUE) != null) {}
    }

    @AfterEach
    public void cleanup() {
        paymentRepository.deleteAll();
    }

    @Test
    public void testFullPaymentFlow() throws InterruptedException {
        // 1️⃣ Prepare request
        PaymentRequest request = new PaymentRequest();
        setPaymentRequest(request, 123L, "user1", new BigDecimal("100.0"));

        // 2️⃣ Call controller
        ResponseEntity<PaymentResponse> response = paymentController.payment(request);
        assertNotNull(response);
        PaymentResponse body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.getPaymentNo());
        assertEquals(PaymentStatus.PENDING, body.getStatus());

        // 3️⃣ Verify database
        Optional<Payment> paymentOptional = paymentRepository.findPaymentByPaymentNo(body.getPaymentNo());
        assertTrue(paymentOptional.isPresent());
        Payment payment = paymentOptional.get();
        assertEquals(request.getOrderId(), payment.getOrderId());
        assertTrue(request.getAmount().compareTo(payment.getAmount()) == 0);
        assertTrue(payment.getStatus() == PaymentStatus.PAID || payment.getStatus() == PaymentStatus.FAILED);

        // 4️⃣ Receive message from MQ (wait up to 2 seconds)
        PaymentStatusMessage statusMessage = null;
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 2000) {
            Object mqMessage = testRabbitTemplate.receiveAndConvert(PAYMENT_STATUS_QUEUE);
            if (mqMessage != null) {
                assertTrue(mqMessage instanceof PaymentStatusMessage);
                statusMessage = (PaymentStatusMessage) mqMessage;
                break;
            }
            Thread.sleep(50);
        }
        assertNotNull(statusMessage, "MQ message should not be null");

        // 5️⃣ Verify MQ message content
        assertEquals(payment.getPaymentNo(), statusMessage.getPaymentNo());
        assertEquals(payment.getOrderId(), statusMessage.getOrderId());
        assertEquals(payment.getStatus(), statusMessage.getStatus());

    }

    // helper: set private fields of PaymentRequest
    private void setPaymentRequest(PaymentRequest request, Long orderId, String userId, BigDecimal amount) {
        try {
            var fieldOrderId = PaymentRequest.class.getDeclaredField("orderId");
            fieldOrderId.setAccessible(true);
            fieldOrderId.set(request, orderId);

            var fieldUserId = PaymentRequest.class.getDeclaredField("userId");
            fieldUserId.setAccessible(true);
            fieldUserId.set(request, userId);

            var fieldAmount = PaymentRequest.class.getDeclaredField("amount");
            fieldAmount.setAccessible(true);
            fieldAmount.set(request, amount);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
