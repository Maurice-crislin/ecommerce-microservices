package org.example.searchservice.document;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.common.product.enums.CategoryCode;
import org.common.product.enums.ProductStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "product_search")
public class ProductDoc {
    @Id
    @Field(type = FieldType.Long)
    private Long productCode;

    @Field(type = FieldType.Text)
    private String productName;

    @Field(type = FieldType.Double) // elastic不支持BigDecimal
    private Double price;

    // 枚举精确匹配
    @Field(type = FieldType.Keyword)
    private ProductStatus status;

    // 商品描述需要全文搜索
    @Field(type = FieldType.Text)
    private String description;

    // 品牌通常精确过滤
    @Field(type = FieldType.Keyword)
    private String brand;

    // 枚举精确匹配
    @Field(type = FieldType.Keyword)
    private CategoryCode categoryCode;
}
