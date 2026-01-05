package org.example.inventoryservice.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "inventory_operation",
        uniqueConstraints = {
            @UniqueConstraint(columnNames = {"order_id", "operation_type"})
        }
)
public class InventoryOperation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @NotNull
    private Long orderId;
    @NotNull
    @Enumerated(EnumType.STRING)
    private OperationType operationType;
    @NotNull
    @Enumerated(EnumType.STRING)
    private OperationStatus operationStatus;
    @NotNull
    private LocalDateTime createdAt;
    @NotNull
    private LocalDateTime updatedAt;

    @Version
    /* optimistic locking*/
    private  Long version;

    private InventoryOperation(Long orderId, OperationType operationType) {
        this.orderId = orderId;
        this.operationType = operationType;
        this.operationStatus = OperationStatus.PROCESSING;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public static InventoryOperation start(Long orderId, OperationType operationType){
        return new InventoryOperation(orderId, operationType);
    }

    public void markSuccess (){
        this.operationStatus = OperationStatus.SUCCESS;
        this.updatedAt = LocalDateTime.now();
    }
    public void markFailed (){
        this.operationStatus = OperationStatus.FAILED;
        this.updatedAt = LocalDateTime.now();
    }
}
