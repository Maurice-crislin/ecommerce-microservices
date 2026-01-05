package org.example.paymentservice;

import org.common.payment.dto.RefundResponse;
import org.common.payment.enums.PaymentStatus;
import org.common.payment.message.PaymentStatusMessage;
import org.example.paymentservice.controller.PaymentController;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.dto.PaymentResponse;
import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.Refund;
import org.common.payment.enums.RefundStatus;
import org.example.paymentservice.messaging.RefundStatusMessage;
import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.repository.RefundRepository;
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
public class PaymentRefundIntegrationTest {

    @Autowired
    private PaymentController paymentController;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private RabbitTemplate testRabbitTemplate;

    private final String PAYMENT_STATUS_QUEUE = "payment_status_queue";
    private final String REFUND_STATUS_QUEUE = "refund_status_queue";

    @BeforeEach
    public void setup() {

        testRabbitTemplate = new RabbitTemplate(rabbitTemplate.getConnectionFactory());
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTrustedPackages("org.example.paymentservice.messaging");
        converter.setJavaTypeMapper(typeMapper);
        testRabbitTemplate.setMessageConverter(converter);

        // clear queues
        while (testRabbitTemplate.receive(PAYMENT_STATUS_QUEUE) != null) {}
        while (testRabbitTemplate.receive(REFUND_STATUS_QUEUE) != null) {}
    }

    @BeforeEach
    public void cleanup() {
        refundRepository.deleteAll();
        paymentRepository.deleteAll();

        refundRepository.flush(); // 强制同步到数据库
        paymentRepository.flush();
    }

    // =================== Payment 测试 ===================
    @Test
    public void testPaymentFlow() throws InterruptedException {
        PaymentRequest request = new PaymentRequest();
        setPaymentRequest(request, 1001L, "user1", new BigDecimal("50.0"));

        ResponseEntity<PaymentResponse> response = paymentController.payment(request);
        assertNotNull(response);
        PaymentResponse body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.getPaymentNo());
        assertEquals(PaymentStatus.PROCESSING, body.getStatus());

        // 验证数据库
        Payment payment = paymentRepository.findPaymentByPaymentNo(body.getPaymentNo()).orElseThrow();
        assertEquals(request.getOrderId(), payment.getOrderId());
        assertTrue(payment.getStatus() == PaymentStatus.PAID || payment.getStatus() == PaymentStatus.FAILED);


        // 验证 MQ 消息
        PaymentStatusMessage statusMessage = receivePaymentMessage(body.getPaymentNo(), 5000);
        assertNotNull(statusMessage);
        assertEquals(payment.getPaymentNo(), statusMessage.getPaymentNo());
        assertEquals(payment.getStatus(), statusMessage.getStatus());
    }

    // =================== Refund 测试 ===================
    @Test
    public void testRefundFlow() throws InterruptedException {
        // 1️⃣ 创建已支付 Payment
        Payment payment = new Payment();
        payment.setOrderId(2001L);
        payment.setAmount(new BigDecimal("120.0"));
        payment.setPaymentNo("PAY-" + System.currentTimeMillis());
        payment.setStatus(PaymentStatus.PAID);
        payment.setProvider("SIMULATED");
        paymentRepository.save(payment);

        // 2️⃣ 调用 refund API
        ResponseEntity<RefundResponse> refundResponseEntity =
                paymentController.refund(payment.getPaymentNo());
        RefundResponse refundResponse = refundResponseEntity.getBody();
        assertNotNull(refundResponse);
        assertNotNull(refundResponse.getRefundNo());
        System.out.println("refundResponse.getStatus() " + refundResponse.getStatus());

        // 3️⃣ 验证数据库
        Refund refund = refundRepository.findByRefundNo(refundResponse.getRefundNo()).orElseThrow();
        System.out.println("refund.getStatus() " + refund.getStatus());
        assertEquals(payment.getPaymentNo(), refund.getPaymentNo());
        // assertEquals(refund.getStatus(), RefundStatus.PROCESSING);

        // 4️⃣ 等待异步处理 + MQ 消息
        RefundStatusMessage refundMessage = receiveRefundMessage(refund.getRefundNo(), 5000);
        assertNotNull(refundMessage);
        assertEquals(refund.getRefundNo(), refundMessage.getRefundNo());
        assertEquals(refund.getPaymentNo(), refundMessage.getPaymentNo());
        System.out.println("refundMessage.getStatus() " + refundMessage.getStatus());
        assertTrue(refundMessage.getStatus() == RefundStatus.SUCCESS || refundMessage.getStatus() == RefundStatus.FAILED);
        // mq status is same as database
        assertEquals(refundMessage.getStatus(), refund.getStatus());

        // 5️⃣ 数据库状态已更新
        Refund refundUpdated = refundRepository.findByRefundNo(refund.getRefundNo()).orElseThrow();
        assertEquals(refundMessage.getStatus(), refundUpdated.getStatus());
    }

    // =================== Helper 方法 ===================
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

    private PaymentStatusMessage receivePaymentMessage(String paymentNo, long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            Object msg = testRabbitTemplate.receiveAndConvert(PAYMENT_STATUS_QUEUE);
            if (msg instanceof PaymentStatusMessage) {
                PaymentStatusMessage message = (PaymentStatusMessage) msg;
                if (message.getPaymentNo().equals(paymentNo)) return message;
            }
            Thread.sleep(50);
        }
        return null;
    }

    private RefundStatusMessage receiveRefundMessage(String refundNo, long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            Object msg = testRabbitTemplate.receiveAndConvert(REFUND_STATUS_QUEUE);
            if (msg instanceof RefundStatusMessage) {
                RefundStatusMessage message = (RefundStatusMessage) msg;
                if (message.getRefundNo().equals(refundNo)) return message;
            }
            Thread.sleep(50);
        }
        return null;
    }
}
