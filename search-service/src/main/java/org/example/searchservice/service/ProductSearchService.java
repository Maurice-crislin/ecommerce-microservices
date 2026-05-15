package org.example.searchservice.service;

import org.example.searchservice.document.ProductDoc;
import org.example.searchservice.dto.ProductSearchRequest;
import org.springframework.data.elasticsearch.core.SearchPage;

import java.util.List;

public interface ProductSearchService {
    public List<ProductDoc> searchProducts(ProductSearchRequest request);
}
