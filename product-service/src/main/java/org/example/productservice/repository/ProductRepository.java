package org.example.productservice.repository;

import lombok.extern.jackson.Jacksonized;
import org.example.productservice.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findProductByProductCode(Long productCode);
    List<Product> findProductsByProductCodeIn(List<Long> productCodes);

}
