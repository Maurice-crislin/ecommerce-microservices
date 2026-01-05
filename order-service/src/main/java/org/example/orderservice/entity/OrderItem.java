package org.example.orderservice.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;


import java.math.BigDecimal;

@Entity
@Data
@Table(name="order_items")
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @NotNull
    private Long productCode;
    @Min(1)
    @NotNull
    private Integer quantity;
    @NotNull
    @DecimalMin(value = "0.0", inclusive = false, message = "unit price must be greater than 0")
    private BigDecimal unitPrice;
    @ManyToOne
    @JoinColumn(name="order_id")
    private Order order;
}
