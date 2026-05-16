package org.example.productservice.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.common.product.enums.CategoryCode;

@Getter
@Setter
@Entity
@Table(name="product_detail")
public class ProductDetail {
    @Id
    private Long productId;

    @JsonIgnore // 序列化时会忽略反向引用,在正向引用那边不要忽略
    @OneToOne(fetch = FetchType.LAZY) // 1-1的对应
    @MapsId // 使用关联对象Product的主键值作为当前实体的主键值。
    @JoinColumn(name="product_id")
    private Product product;

    @Column(length = 2000)
    private String description;

    @Column(nullable = false)
    private String brand;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CategoryCode categoryCode;
}
