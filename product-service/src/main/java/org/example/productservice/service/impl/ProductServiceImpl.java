package org.example.productservice.service.impl;


import lombok.RequiredArgsConstructor;
import org.example.productservice.dto.ProductPriceResponse;
import org.example.productservice.entity.Product;
import org.example.productservice.repository.ProductRepository;
import org.example.productservice.service.ProductService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {
    private final ProductRepository productRepository;
    @Override
    public ProductPriceResponse getProductPrice(Long productCode){
        Product product = productRepository
                .findProductByProductCode(productCode)
                .orElseThrow(()-> new IllegalArgumentException("Product not found: " + productCode));
        return mapToProductPriceResponse(product);
    }
    @Override
    public  List<ProductPriceResponse> getProductPrices(List<Long> productCodes){
        List<Product> products = productRepository
                .findProductsByProductCodeIn(productCodes);

        return products.stream()
                .map(this::mapToProductPriceResponse)
                .collect(Collectors.toList());
    }
    private  ProductPriceResponse mapToProductPriceResponse(Product product){
        return new ProductPriceResponse(
                product.getProductCode(),
                product.getPrice(),
                product.getStatus()
        );
    }
}
