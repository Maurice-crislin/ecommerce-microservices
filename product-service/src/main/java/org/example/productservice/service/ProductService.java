package org.example.productservice.service;

import org.example.productservice.dto.BatchProductPriceResponse;
import org.example.productservice.dto.ProductPriceResponse;

import java.util.List;

public interface ProductService {
    ProductPriceResponse getProductPrice(Long productCode);
    BatchProductPriceResponse getBatchProductPrices(List<Long> productCodes);
    List<ProductPriceResponse> getProductPrices(List<Long> productCodes);
}
