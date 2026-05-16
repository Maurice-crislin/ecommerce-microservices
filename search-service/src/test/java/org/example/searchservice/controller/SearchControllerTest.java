package org.example.searchservice.controller;

import org.example.searchservice.document.ProductDoc;
import org.example.searchservice.dto.ProductSearchRequest;
import org.example.searchservice.service.ProductSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.common.product.enums.CategoryCode;
import org.common.product.enums.ProductStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SearchController}.
 * <p>
 * Verifies that the controller correctly delegates to the service
 * and returns the expected HTTP response.
 */
@ExtendWith(MockitoExtension.class)
class SearchControllerTest {

    @Mock
    private ProductSearchService productSearchService;

    @InjectMocks
    private SearchController searchController;

    @Test
    @DisplayName("GET /search should return 200 with search results")
    void searchProductsReturns200WithResults() {
        // Given
        ProductSearchRequest request = new ProductSearchRequest(
                "laptop",
                BigDecimal.valueOf(500),
                BigDecimal.valueOf(2000),
                "Dell",
                CategoryCode.LAPTOP,
                null, null,
                0, 20
        );

        ProductDoc expectedDoc = new ProductDoc(
                1001L, "XPS 15", 1899.0, ProductStatus.ACTIVE,
                "Powerful laptop", "Dell", CategoryCode.LAPTOP
        );

        when(productSearchService.searchProducts(any(ProductSearchRequest.class)))
                .thenReturn(List.of(expectedDoc));

        // When
        ResponseEntity<List<ProductDoc>> response = searchController.searchProducts(request);

        // Then
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).getProductCode()).isEqualTo(1001L);
        assertThat(response.getBody().get(0).getProductName()).isEqualTo("XPS 15");
        assertThat(response.getBody().get(0).getPrice()).isEqualTo(1899.0);
        assertThat(response.getBody().get(0).getBrand()).isEqualTo("Dell");
        assertThat(response.getBody().get(0).getCategoryCode()).isEqualTo(CategoryCode.LAPTOP);
        assertThat(response.getBody().get(0).getStatus()).isEqualTo(ProductStatus.ACTIVE);

        verify(productSearchService).searchProducts(any(ProductSearchRequest.class));
    }

    @Test
    @DisplayName("GET /search should return 200 with empty list when no results")
    void searchProductsReturns200WithEmptyList() {
        // Given
        ProductSearchRequest request = new ProductSearchRequest(
                "nonexistent", null, null, null, null, null, null, 0, 10
        );

        when(productSearchService.searchProducts(any(ProductSearchRequest.class)))
                .thenReturn(List.of());

        // When
        ResponseEntity<List<ProductDoc>> response = searchController.searchProducts(request);

        // Then
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEmpty();
        verify(productSearchService).searchProducts(any(ProductSearchRequest.class));
    }

    @Test
    @DisplayName("GET /search should pass the request to the service unchanged")
    void searchProductsPassesRequestToService() {
        // Given
        ProductSearchRequest request = new ProductSearchRequest(
                "phone",
                BigDecimal.valueOf(300),
                BigDecimal.valueOf(1500),
                "Google",
                CategoryCode.SMARTPHONE,
                null, null,
                1, 5
        );

        when(productSearchService.searchProducts(any(ProductSearchRequest.class)))
                .thenReturn(List.of());

        // When
        searchController.searchProducts(request);

        // Then
        verify(productSearchService).searchProducts(argThat(r ->
                "phone".equals(r.getKeyword()) &&
                r.getMinPrice().compareTo(BigDecimal.valueOf(300)) == 0 &&
                r.getMaxPrice().compareTo(BigDecimal.valueOf(1500)) == 0 &&
                "Google".equals(r.getBrand()) &&
                CategoryCode.SMARTPHONE.equals(r.getCategoryCode()) &&
                r.getPage() == 1 &&
                r.getSize() == 5
        ));
    }

    @Test
    @DisplayName("GET /search with default request should work")
    void searchProductsWithDefaults() {
        // Given
        ProductSearchRequest request = new ProductSearchRequest(
                null, null, null, null, null, null, null, null, null
        );

        when(productSearchService.searchProducts(any(ProductSearchRequest.class)))
                .thenReturn(List.of());

        // When
        ResponseEntity<List<ProductDoc>> response = searchController.searchProducts(request);

        // Then
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEmpty();
        verify(productSearchService).searchProducts(any(ProductSearchRequest.class));
    }

}