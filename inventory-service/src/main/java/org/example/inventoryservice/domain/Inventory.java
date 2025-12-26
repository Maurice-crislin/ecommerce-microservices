package org.example.inventoryservice.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name="inventory")
public class Inventory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long productCode;
    private Integer availableStock;
    @Version
    /* optimistic locking*/
    private  Long version;
    public Inventory(Long productCode, Integer availableStock) {
        this.productCode = productCode;
        this.availableStock = availableStock;
    }
    public void deductStock(Integer quantity) {
        if ( quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than zero");
        }
        if (quantity > this.availableStock) {
           throw new IllegalArgumentException("out of stock");
        }
        this.availableStock -= quantity;
    }
}
