package org.example.productservice.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.productservice.enums.ProductStatus;

import java.math.BigDecimal;

@Data
@Entity
@AllArgsConstructor
@NoArgsConstructor
@Table(name="products")
public class Product {
    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private Long id;
    @Column(name = "product_code", unique = true, nullable = false)
    private Long productCode;
    @Column(name = "product_name", nullable = false)
    private String productName;
    @Column(nullable = false)
    private BigDecimal price;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductStatus status;

    // 添加级联操作，这样保存 Product 时会自动保存/更新 ProductDetail
    @OneToOne(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private ProductDetail productDetail;

    public void setProductDetail(ProductDetail productDetail) {
        if (productDetail != null) {
            this.productDetail = productDetail;
            this.productDetail.setProduct(this);
        }
    }

    @PrePersist
    public void prePersist() {
        if (status == null) {
            status = ProductStatus.ACTIVE;
        }
    }
}
