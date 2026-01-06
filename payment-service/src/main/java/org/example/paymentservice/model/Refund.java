package org.example.paymentservice.model;

import jakarta.persistence.*;
import lombok.Data;
import org.common.payment.enums.RefundStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name="refunds",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = "refund_no"),
                @UniqueConstraint(columnNames = "payment_no"),
                // @UniqueConstraint(columnNames = {"order_id","payment_no","refund_no"})
        }
)
public class Refund {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "refund_no", nullable = false, unique = true)
    private String refundNo;

    @Column(name = "payment_no", nullable = false)
    private String paymentNo;

    @Column(name = "order_id",nullable = false)
    private Long orderId;


    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefundStatus status;

    private String provider;

    private String providerRefundId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
