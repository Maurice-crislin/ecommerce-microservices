package org.example.paymentservice.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import org.common.payment.enums.PaymentStatus;


@Data
@Entity
@Table(name="payments",
        uniqueConstraints = {
        @UniqueConstraint(columnNames = "order_id")
        }
)
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "order_id", nullable = false)
    private Long orderId;
    @Column(name = "payment_no", nullable = false,unique = true)
    private String paymentNo;
    @Column(nullable = false)
    private BigDecimal amount;
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;
    private String provider;
    @Column(name = "provider_tx_id")
    private String providerTxId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /* use @PrePersist and @PreUpdate
    to automatically maintain createdAt and updatedAt timestamps*/
    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
