package org.example.orderservice.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.common.order.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name="orders",uniqueConstraints = {
        @UniqueConstraint(columnNames = {"idempotency_key"})
})
public class Order {
    @Id
    private Long orderId;
    @NotNull
    private String userId;
    @NotNull
    private BigDecimal totalAmount;
    @Enumerated(EnumType.STRING)
    private OrderStatus orderStatus;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    @NotNull
    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Version
    private Long version;

    public Order(Long orderId, String userId) {
        this.orderId = orderId;
        this.userId = userId;
    }


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

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    private List<OrderItem> orderItems;

    public void initState() {
        this.orderStatus = OrderStatus.PROCESSING;
    }

    public void pay() {
        if (this.orderStatus != OrderStatus.AWAITING_PAYMENT) {
            throw new IllegalStateException(
                    "Only AWAITING_PAYMENT orders can be paid"
            );
        }

        this.orderStatus = OrderStatus.PAID;
    }
    public void cancel(){
        if(this.orderStatus != OrderStatus.AWAITING_PAYMENT){
            throw new IllegalStateException(
                    "Only AWAITING_PAYMENT orders can be cancelled"
            );
        }
        this.orderStatus = OrderStatus.CANCELED;
    }
    public void timeout(){
        if(this.orderStatus != OrderStatus.AWAITING_PAYMENT){
            throw new IllegalStateException(
                    "Only AWAITING_PAYMENT orders can be timeout"
            );
        }
        this.orderStatus = OrderStatus.TIMEOUT;
    }
    public void fail(){
        if(this.orderStatus != OrderStatus.AWAITING_PAYMENT){
            throw new IllegalStateException(
                    "Only AWAITING_PAYMENT orders can be failed"
            );
        }
        this.orderStatus = OrderStatus.FAILED;
    }
}
