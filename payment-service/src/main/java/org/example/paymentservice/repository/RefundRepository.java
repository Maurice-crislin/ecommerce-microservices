package org.example.paymentservice.repository;

import org.example.paymentservice.model.Refund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RefundRepository extends JpaRepository<Refund, Long> {
    Optional<Refund> findByRefundNo(String refundNo);
    Optional<Refund> findByPaymentNo(String paymentNo);
}
