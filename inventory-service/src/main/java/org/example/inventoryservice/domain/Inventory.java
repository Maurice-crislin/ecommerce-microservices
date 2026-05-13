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
    // 当前在库实物总量（= Redis available + Redis locked）
    private Integer onHandStock;
    // 已售卖库存 作为审计数据
    private Integer soldStock;

    @Version
    /* optimistic locking*/
    private  Long version;

    private void validateState(){
        if(this.onHandStock < 0 || this.soldStock < 0){
            throw new IllegalStateException(
                    "Invalid inventory state: soldStock=" + soldStock
                            + ", onHandStock=" + onHandStock
            );
        }
    }
    @PrePersist
    @PreUpdate
    private void prePersistUpdate(){
        // always check inventory
        validateState();
    }
    public Inventory(Long productCode, Integer onHandStock) {
        this.productCode = productCode;
        this.onHandStock = onHandStock;
        this.soldStock = 0;
    }


    /**
     * admin deduct
     * @param quantity
     */
    public void deductStock(Integer quantity) {
        if ( quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than zero");
        }
        if (quantity > this.onHandStock) {
           throw new IllegalArgumentException("out of stock");
        }
        this.onHandStock -= quantity;
    }

    /**
     * admin deduct
     * @param quantity
     */
    public void increaseStock(Integer quantity) {
        if ( quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than zero");
        }
        this.onHandStock += quantity;
    }


    public void confirmSale(Integer quantity) {
        if ( quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than zero");
        }
        if (quantity > this.onHandStock) {
            throw new IllegalArgumentException("Not enough locked stock to confirm sale");
        }
        this.onHandStock -= quantity;
        this.soldStock += quantity;
    }

}
