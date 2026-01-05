package org.example.productservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.productservice.dto.BatchProductPriceRequest;
import org.example.productservice.dto.BatchProductPriceResponse;
import org.example.productservice.dto.ProductPriceResponse;
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
    public ResponseEntity<ProductPriceResponse>getProductPrice(@PathVariable Long productCode){
        try {
            ProductPriceResponse productPriceResponse = productService.getProductPrice(productCode);
            return ResponseEntity.ok(productPriceResponse);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build(); // 返回 404
        }
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
}
