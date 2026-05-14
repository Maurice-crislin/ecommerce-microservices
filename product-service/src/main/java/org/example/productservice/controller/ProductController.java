package org.example.productservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.productservice.dto.*;
import org.example.productservice.entity.Product;
import org.example.productservice.service.ProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {
    private final ProductService productService;
    /**
     * single product
     * GET /products/{productCode}
     */
    @GetMapping("/{productCode}")
    public ResponseEntity<ProductPriceResponse>getProductPrice(@PathVariable(name = "productCode") Long productCode){
        ProductPriceResponse productPriceResponse = productService.getProductPrice(productCode);
        return ResponseEntity.ok(productPriceResponse);
    }

    /**
     * batch products
     * POST /products/batch
     * Content-Type: application/json
     *
     * {
     *   "codes": [10010001, 10010002, 10010003]
     * }
     * @return
     * {
     *    orderable: true,
     *    products : [{
     *     "productCode": 10010001,
     *     "price": 199.99,
     *     "status": "ACTIVE"
     *   },
     *   {
     *     "productCode": 10010002,
     *     "price": 299.99,
     *     "status": "ACTIVE"
     *   }],
     *   missingProductCodes: [10010003]
     * }
     */
    @PostMapping("/batch")
    public ResponseEntity<BatchProductPriceResponse> getAllProductPrices(@RequestBody @Valid BatchProductPriceRequest request) {
        List<Long> productCodes = request.getProductCodes();
        if (request.getProductCodes() == null || request.getProductCodes().isEmpty()) {
            BatchProductPriceResponse response =
                    new BatchProductPriceResponse(false,Collections.emptyList(),Collections.emptyList());
            return ResponseEntity.badRequest().body(response);
        }
        return ResponseEntity.ok(productService.getBatchProductPrices(productCodes));
    }
    /**
     * 删除商品（级联删除 Detail）
     * Delete /products/{productCode}
     */
    @DeleteMapping("/{productCode}")
    public ResponseEntity<Void> deleteProduct(@PathVariable(name = "productCode") Long productCode){
        productService.deleteProduct(productCode);
        return ResponseEntity.noContent().build(); // 204  no content
    }

    /**
     * 创建商品（同时创建 Product 和 ProductDetail）
     * POST /products
     */
    @PostMapping
    public ResponseEntity<ProductResponse> addProduct(@Valid @RequestBody ProductCreateRequest productCreateRequest){
        return ResponseEntity.ok(productService.addProduct(productCreateRequest));
    }
    /**
     * 更新商品（同时更新两张表）
     * PUT /products/{productCode}
     */
    @PutMapping("/{productCode}")
    public ResponseEntity<ProductResponse> updateProduct(@PathVariable(name = "productCode") Long productCode, @RequestBody @Valid ProductUpdateRequest productUpdateRequest){

        ProductResponse productResponse = productService.updateProduct(productCode, productUpdateRequest);
        return ResponseEntity.ok(productResponse);
    }
}
