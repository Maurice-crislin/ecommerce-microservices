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

    /**
     * 被 getBatchProductPrices 调用,属于基础方法, 只负责批量查询数据并转换为 DTO
     * 不对接任何外部调用端点
     * @param productCodes
     * @return
     */
    @Override
    public  List<ProductPriceResponse> getProductPrices(List<Long> productCodes){
        List<Product> products = productRepository
                .findProductsByProductCodeIn(productCodes);

        return products.stream()
                .map(this::mapToProductPriceResponse)
                .collect(Collectors.toList());
    }

    /**
     * 业务增强方法，在查询基础(getProductPrices)上增加了：按请求顺序重排、识别缺失产品、计算可下单状态
     * @param productCodes
     * @return
     */
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
    @Override
    public void deleteProduct(Long productCode){
        productRepository.deleteById(productCode);
    }
}
