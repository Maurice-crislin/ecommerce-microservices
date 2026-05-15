package org.example.searchservice.controller;

import lombok.RequiredArgsConstructor;
import org.example.searchservice.document.ProductDoc;
import org.example.searchservice.dto.ProductSearchRequest;
import org.example.searchservice.service.ProductSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class SearchController {
    private final ProductSearchService productSearchService;

    @GetMapping("/search")
    ResponseEntity<List<ProductDoc>> searchProducts(ProductSearchRequest request){
        return ResponseEntity.ok(productSearchService.searchProducts(request));
    }
}
