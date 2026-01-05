package org.example.productservice.service.impl;


import lombok.RequiredArgsConstructor;
import org.example.productservice.dto.BatchProductPriceResponse;
import org.example.productservice.dto.ProductPriceResponse;
import org.example.productservice.entity.Product;
import org.example.productservice.enums.ProductStatus;
import org.example.productservice.repository.ProductRepository;
import org.example.productservice.service.ProductService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    @Override
    public BatchProductPriceResponse getBatchProductPrices(List<Long> productCodes){
        List<ProductPriceResponse> products = getProductPrices(productCodes);


        Map<Long, ProductPriceResponse> productMap = products.stream()
                .collect(Collectors.toMap(ProductPriceResponse::getProductCode,
                        productPriceResponse -> productPriceResponse)
                );
        // reorder by productCodes
        List<ProductPriceResponse> orderedProducts = productCodes.stream().map(productMap::get).filter(Objects::nonNull).toList();


        List<Long> missingProductCodes = productCodes.stream().filter(code -> !productMap.containsKey(code)).toList();

        boolean allProductsOrderable = missingProductCodes.isEmpty() && orderedProducts.stream().allMatch(p -> p != null && p.getStatus() == ProductStatus.ACTIVE);


        return new BatchProductPriceResponse(allProductsOrderable, orderedProducts, missingProductCodes);
    }
    private  ProductPriceResponse mapToProductPriceResponse(Product product){
        return new ProductPriceResponse(
                product.getProductCode(),
                product.getPrice(),
                product.getStatus()
        );
    }
}
