package org.example.searchservice.repository;

import org.example.searchservice.document.ProductDoc;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductRepository
        extends ElasticsearchRepository<ProductDoc, Long> {
    void deleteByProductCode(Long productCode);
}