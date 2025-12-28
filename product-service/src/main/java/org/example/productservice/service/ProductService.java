package org.example.productservice.service;

import org.example.productservice.dto.ProductPriceResponse;

import java.util.List;

public interface ProductService {
    ProductPriceResponse getProductPrice(Long productCode);
    List<ProductPriceResponse> getProductPrices(List<Long> productCodes);
}
