package org.example.searchservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.common.product.enums.CategoryCode;
import org.common.product.enums.ProductStatus;
import java.math.BigDecimal;

@Getter
@Setter
@AllArgsConstructor
public class ProductSearchRequest {
    // 关键词：会同时搜索 productName 和 description
    private String keyword;

    // 价格区间
    private BigDecimal minPrice;
    private BigDecimal maxPrice;

    // 精确匹配：品牌
    private String brand;

    // 精确匹配：分类
    private CategoryCode categoryCode;

    // 排序字段：可选 "price", "productCode", "productName" 等
    private String sortField;
    // 排序方向：asc 或 desc
    private String sortOrder;

    // 分页参数
    private Integer page = 0;   // 默认第0页
    private Integer size = 20;  // 默认每页20条
}