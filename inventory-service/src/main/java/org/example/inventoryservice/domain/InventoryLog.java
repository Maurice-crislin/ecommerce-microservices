package org.example.inventoryservice.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@Table(name="inventory_log")
public class InventoryLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @NotNull
    private Long productCode;
    private Integer quantity;
    // admin操作时可能没有orderId
    private Long orderId;
    @NotNull
    @Enumerated(EnumType.STRING)
    private OperationType operationType;
    private LocalDateTime createdAt;

    @PrePersist
    private void prePersistUpdate(){
        this.createdAt = LocalDateTime.now();
    }

    public InventoryLog(Long productCode, Integer quantity, Long orderId, OperationType operationType) {
        this.productCode = productCode;
        this.quantity = quantity;
        this.orderId = orderId; // 可能为空
        this.operationType = operationType;
    }
}
