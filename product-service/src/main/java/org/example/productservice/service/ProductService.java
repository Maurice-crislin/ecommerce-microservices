package org.example.productservice.service;

import org.example.productservice.dto.*;

import java.util.List;

public interface ProductService {
    ProductPriceResponse getProductPrice(Long productCode);
    BatchProductPriceResponse getBatchProductPrices(List<Long> productCodes);
    List<ProductPriceResponse> getProductPrices(List<Long> productCodes);
    void deleteProduct(Long productCode);
    ProductResponse addProduct(ProductCreateRequest productCreateRequest);
    ProductResponse updateProduct(Long productCode, ProductUpdateRequest productUpdateRequest);
}
