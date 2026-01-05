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
    private Integer lockedStock;
    private Integer soldStock;

    @Version
    /* optimistic locking*/
    private  Long version;

    private void validateState(){
        if(this.availableStock < 0 || this.lockedStock < 0 || this.soldStock < 0){
            throw new IllegalStateException(
                    "Invalid inventory state: available=" + availableStock
                            + ", locked=" + lockedStock
                            + ", sold=" + soldStock
            );
        }
    }
    @PrePersist
    @PreUpdate
    private void prePersistUpdate(){
        // always check inventory
        validateState();
    }
    public Inventory(Long productCode, Integer availableStock) {
        this.productCode = productCode;
        this.availableStock = availableStock;
        this.lockedStock = 0;
        this.soldStock = 0;
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
    public void increaseStock(Integer quantity) {
        if ( quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than zero");
        }
        this.availableStock += quantity;
    }

    public void lock(Integer quantity) {
        if ( quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than zero");
        }
        if (quantity > this.availableStock) {
            throw new IllegalArgumentException("out of stock");
        }
        this.availableStock -= quantity;
        this.lockedStock += quantity;
    }
    public void confirmSale(Integer quantity) {
        if ( quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than zero");
        }
        if (quantity > this.lockedStock) {
            throw new IllegalArgumentException("Not enough locked stock to confirm sale");
        }
        this.lockedStock -= quantity;
        this.soldStock += quantity;
    }
    public  void unlock(Integer quantity) {
        if ( quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than zero");
        }
        if (quantity > this.lockedStock) {
            throw new IllegalArgumentException("Cannot unlock more than locked stock");
        }
        this.lockedStock -= quantity;
        this.availableStock += quantity;
    }
}
