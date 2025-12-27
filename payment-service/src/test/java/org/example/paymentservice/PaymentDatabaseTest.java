package org.example.paymentservice;

import org.example.paymentservice.model.Payment;
import org.example.paymentservice.model.PaymentStatus;
import org.example.paymentservice.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
public class PaymentDatabaseTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    void testDatabaseConnectionAndCRUD() {
        // 1️⃣ create a payment record
        Payment payment = new Payment();
        payment.setOrderId(5001L);
        payment.setPaymentNo("test-uuid-5001");
        payment.setAmount(BigDecimal.valueOf(120.50));
        payment.setStatus(PaymentStatus.PENDING);
        payment.setProvider("Stripe");
        payment.setProviderTxId(null);

        Payment savedPayment = paymentRepository.save(payment);

        System.out.println("Payment saved: " + savedPayment.getPaymentNo() + " - " + savedPayment.getStatus());

        // 2️⃣ find payment by paymentNo
        Optional<Payment> result = paymentRepository.findPaymentByPaymentNo("test-uuid-5001");
        assertTrue(result.isPresent());
        assertEquals(PaymentStatus.PENDING, result.get().getStatus());

        System.out.println("Payment fetched: " + result.get().getPaymentNo() + " - " + result.get().getStatus());

        // 3️⃣ update payment status
        Payment fetched = result.get();
        fetched.setStatus(PaymentStatus.PAID);
        paymentRepository.save(fetched);

        Optional<Payment> updated = paymentRepository.findPaymentByPaymentNo("test-uuid-5001");
        assertTrue(updated.isPresent());
        assertEquals(PaymentStatus.PAID, updated.get().getStatus());

        System.out.println("Payment updated: " + updated.get().getPaymentNo() + " - " + updated.get().getStatus());

        // 4️⃣ delete record
        paymentRepository.delete(fetched);
        Optional<Payment> deleted = paymentRepository.findPaymentByPaymentNo("test-uuid-5001");
        assertTrue(deleted.isEmpty());

        System.out.println("Payment record deleted successfully");
    }
}
