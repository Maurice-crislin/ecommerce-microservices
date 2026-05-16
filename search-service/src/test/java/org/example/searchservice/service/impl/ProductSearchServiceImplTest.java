package org.example.searchservice.service.impl;

import org.example.searchservice.document.ProductDoc;
import org.example.searchservice.dto.ProductSearchRequest;
import org.example.searchservice.service.ProductSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.common.product.enums.CategoryCode;
import org.common.product.enums.ProductStatus;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductSearchServiceImplTest {

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @Captor
    private ArgumentCaptor<CriteriaQuery> queryCaptor;

    private ProductSearchService searchService;

    @BeforeEach
    void setUp() {
        searchService = new ProductSearchServiceImpl(elasticsearchOperations);
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    private ProductDoc createProductDoc(Long productCode, String productName, Double price) {
        return new ProductDoc(productCode, productName, price, ProductStatus.ACTIVE,
                productName + " description", "GenericBrand", CategoryCode.ELECTRONICS);
    }

    private ProductDoc createProductDoc(Long productCode, String productName, Double price,
                                         String brand, CategoryCode category, ProductStatus status) {
        return new ProductDoc(productCode, productName, price, status,
                productName + " description", brand, category);
    }

    @SafeVarargs
    @SuppressWarnings("unchecked")
    private void mockSingleHit(ProductDoc... docs) {
        SearchHit<ProductDoc>[] hits = new SearchHit[docs.length];
        for (int i = 0; i < docs.length; i++) {
            SearchHit<ProductDoc> hit = mock(SearchHit.class);
            when(hit.getContent()).thenReturn(docs[i]);
            hits[i] = hit;
        }
        SearchHits<ProductDoc> searchHits = mock(SearchHits.class);
        when(searchHits.stream()).thenReturn(Stream.of(hits));
        when(elasticsearchOperations.search(any(CriteriaQuery.class), eq(ProductDoc.class)))
                .thenReturn(searchHits);
    }

    // =========================================================================
    // Single filter tests
    // =========================================================================

    @Nested
    @DisplayName("Single filter tests")
    class SingleFilterTests {

        @Test
        @DisplayName("Search by keyword only - should match productName or description")
        void searchByKeyword() {
            ProductSearchRequest request = new ProductSearchRequest(
                    "laptop", null, null, null, null, null, null, 0, 20
            );
            ProductDoc expectedDoc = createProductDoc(1001L, "Gaming Laptop", 1299.99);
            mockSingleHit(expectedDoc);

            List<ProductDoc> results = searchService.searchProducts(request);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getProductName()).isEqualTo("Gaming Laptop");
            verify(elasticsearchOperations).search(queryCaptor.capture(), eq(ProductDoc.class));
        }

        @Test
        @DisplayName("Search by empty keyword - should be ignored as if no keyword filter")
        void searchByEmptyKeyword() {
            ProductSearchRequest request = new ProductSearchRequest(
                    "", null, null, null, null, null, null, 0, 20
            );
            ProductDoc expectedDoc = createProductDoc(1002L, "Any Product", 99.99);
            mockSingleHit(expectedDoc);

            List<ProductDoc> results = searchService.searchProducts(request);

            assertThat(results).hasSize(1);
            verify(elasticsearchOperations).search(queryCaptor.capture(), eq(ProductDoc.class));
        }

        @Test
        @DisplayName("Search by brand only - exact match on brand field")
        void searchByBrand() {
            ProductSearchRequest request = new ProductSearchRequest(
                    null, null, null, "Apple", null, null, null, 0, 20
            );
            mockSingleHit(createProductDoc(1003L, "iPhone", 999.0));

            List<ProductDoc> results = searchService.searchProducts(request);

            assertThat(results).hasSize(1);
            verify(elasticsearchOperations).search(queryCaptor.capture(), eq(ProductDoc.class));
        }

        @Test
        @DisplayName("Search by category only - exact match on categoryCode field")
        void searchByCategory() {
            ProductSearchRequest request = new ProductSearchRequest(
                    null, null, null, null, CategoryCode.ELECTRONICS, null, null, 0, 20
            );
            mockSingleHit(createProductDoc(1004L, "TV", 499.0));

            List<ProductDoc> results = searchService.searchProducts(request);

            assertThat(results).hasSize(1);
            verify(elasticsearchOperations).search(queryCaptor.capture(), eq(ProductDoc.class));
        }
    }

    // =========================================================================
    // Price filter tests
    // =========================================================================

    @Nested
    @DisplayName("Price filter tests")
    class PriceFilterTests {

        @Test
        @DisplayName("Search with both minPrice and maxPrice - price between range")
        void searchByPriceBetween() {
            ProductSearchRequest request = new ProductSearchRequest(
                    null, BigDecimal.valueOf(100), BigDecimal.valueOf(500), null, null, null, null, 0, 20
            );
            mockSingleHit(createProductDoc(2001L, "Mid-range Product", 299.0));

            List<ProductDoc> results = searchService.searchProducts(request);

            assertThat(results).hasSize(1);
            verify(elasticsearchOperations).search(queryCaptor.capture(), eq(ProductDoc.class));
        }

        @Test
        @DisplayName("Search with minPrice only - price greater than or equal to min")
        void searchByMinPriceOnly() {
            ProductSearchRequest request = new ProductSearchRequest(
                    null, BigDecimal.valueOf(1000), null, null, null, null, null, 0, 20
            );
            mockSingleHit(createProductDoc(2002L, "Premium Product", 1500.0));

            List<ProductDoc> results = searchService.searchProducts(request);

            assertThat(results).hasSize(1);
            verify(elasticsearchOperations).search(queryCaptor.capture(), eq(ProductDoc.class));
        }

        @Test
        @DisplayName("Search with maxPrice only - price less than or equal to max")
        void searchByMaxPriceOnly() {
            ProductSearchRequest request = new ProductSearchRequest(
                    null, null, BigDecimal.valueOf(50), null, null, null, null, 0, 20
            );
            mockSingleHit(createProductDoc(2003L, "Budget Item", 29.99));

            List<ProductDoc> results = searchService.searchProducts(request);

            assertThat(results).hasSize(1);
            verify(elasticsearchOperations).search(queryCaptor.capture(), eq(ProductDoc.class));
        }

        @Test
        @DisplayName("Search with equal minPrice and maxPrice - exact price match")
        void searchByExactPrice() {
            BigDecimal exactPrice = BigDecimal.valueOf(99.99);
            ProductSearchRequest request = new ProductSearchRequest(
                    null, exactPrice, exactPrice, null, null, null, null, 0, 20
            );
            mockSingleHit(createProductDoc(2004L, "Fixed Price Item", 99.99));

            List<ProductDoc> results = searchService.searchProducts(request);

            assertThat(results).hasSize(1);
            verify(elasticsearchOperations).search(queryCaptor.capture(), eq(ProductDoc.class));
        }
    }

    // =========================================================================
    // Combined filter tests
    // =========================================================================

    @Nested
    @DisplayName("Combined filter tests")
    class CombinedFilterTests {

        @Test
        @DisplayName("Search with all filters combined")
        void searchWithAllFilters() {
            ProductSearchRequest request = new ProductSearchRequest(
                    "phone",
                    BigDecimal.valueOf(500),
                    BigDecimal.valueOf(2000),
                    "Samsung",
                    CategoryCode.SMARTPHONE,
                    null, null,
                    0, 10
            );
            mockSingleHit(createProductDoc(3001L, "Galaxy S25", 1299.0));

            List<ProductDoc> results = searchService.searchProducts(request);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getProductName()).isEqualTo("Galaxy S25");
            verify(elasticsearchOperations).search(queryCaptor.capture(), eq(ProductDoc.class));
        }

        @Test
        @DisplayName("Search with keyword + brand + category")
        void searchWithKeywordBrandCategory() {
            ProductSearchRequest request = new ProductSearchRequest(
                    "watch", null, null,
                    "Apple", CategoryCode.SMARTWATCH, null, null, 0, 20
            );
            mockSingleHit(createProductDoc(3002L, "Apple Watch", 399.0));

            List<ProductDoc> results = searchService.searchProducts(request);

            assertThat(results).hasSize(1);
            verify(elasticsearchOperations).search(queryCaptor.capture(), eq(ProductDoc.class));
        }

        @Test
        @DisplayName("Search with keyword + price range")
        void searchWithKeywordAndPriceRange() {
            ProductSearchRequest request = new ProductSearchRequest(
                    "laptop",
                    BigDecimal.valueOf(800),
                    BigDecimal.valueOf(3000),
                    null, null, null, null, 0, 20
            );
            mockSingleHit(createProductDoc(3003L, "MacBook Pro", 2499.0));

            List<ProductDoc> results = searchService.searchProducts(request);

            assertThat(results).hasSize(1);
            verify(elasticsearchOperations).search(queryCaptor.capture(), eq(ProductDoc.class));
        }
    }

    // =========================================================================
    // Pagination tests
    // =========================================================================

    @Nested
    @DisplayName("Pagination tests")
    class PaginationTests {

        @Test
        @DisplayName("Search with custom pagination - page 2, size 5")
        void searchWithCustomPagination() {
            ProductSearchRequest request = new ProductSearchRequest(
                    null, null, null, null, null, null, null, 2, 5
            );
            mockSingleHit(createProductDoc(4001L, "Paginated Result", 10.0));

            List<ProductDoc> results = searchService.searchProducts(request);

            assertThat(results).hasSize(1);
            verify(elasticsearchOperations).search(queryCaptor.capture(), eq(ProductDoc.class));

            CriteriaQuery query = queryCaptor.getValue();
            assertThat(query.getPageable()).isNotNull();
            assertThat(query.getPageable().getPageNumber()).isEqualTo(2);
            assertThat(query.getPageable().getPageSize()).isEqualTo(5);
        }

        @Test
        @DisplayName("Search with default pagination - page 0, size 20")
        void searchWithDefaultPagination() {
            ProductSearchRequest request = new ProductSearchRequest(
                    "default", null, null, null, null, null, null, null, null
            );
            mockSingleHit(createProductDoc(4002L, "Default Page", 15.0));

            List<ProductDoc> results = searchService.searchProducts(request);

            assertThat(results).hasSize(1);
            verify(elasticsearchOperations).search(queryCaptor.capture(), eq(ProductDoc.class));

            CriteriaQuery query = queryCaptor.getValue();
            assertThat(query.getPageable()).isNotNull();
            assertThat(query.getPageable().getPageNumber()).isEqualTo(0);
            assertThat(query.getPageable().getPageSize()).isEqualTo(20);
        }
    }

    // =========================================================================
    // Edge cases and special scenarios
    // =========================================================================

    @Nested
    @DisplayName("Edge cases and special scenarios")
    class EdgeCaseTests {

        @Test
        @DisplayName("Search with no filters - should return all documents")
        void searchWithNoFilters() {
            ProductSearchRequest request = new ProductSearchRequest(
                    null, null, null, null, null, null, null, null, null
            );
            mockSingleHit(createProductDoc(5001L, "Product A", 100.0),
                    createProductDoc(5002L, "Product B", 200.0));

            List<ProductDoc> results = searchService.searchProducts(request);

            assertThat(results).hasSize(2);
            verify(elasticsearchOperations).search(any(CriteriaQuery.class), eq(ProductDoc.class));
        }

        @Test
        @DisplayName("Search with null keyword - should be ignored")
        void searchWithNullKeyword() {
            ProductSearchRequest request = new ProductSearchRequest(
                    null, null, null, "Sony", CategoryCode.ELECTRONICS, null, null, 0, 10
            );
            mockSingleHit(createProductDoc(5003L, "PlayStation", 499.0));

            List<ProductDoc> results = searchService.searchProducts(request);

            assertThat(results).hasSize(1);
            verify(elasticsearchOperations).search(queryCaptor.capture(), eq(ProductDoc.class));
        }

        @Test
        @DisplayName("Search with null price filters - should not add price criteria")
        void searchWithNullPrice() {
            ProductSearchRequest request = new ProductSearchRequest(
                    "tablet", null, null, null, null, null, null, 0, 20
            );
            mockSingleHit(createProductDoc(5004L, "iPad", 599.0));

            List<ProductDoc> results = searchService.searchProducts(request);

            assertThat(results).hasSize(1);
            verify(elasticsearchOperations).search(queryCaptor.capture(), eq(ProductDoc.class));
        }

        @Test
        @DisplayName("Empty search result - should return empty list")
        void searchReturnsEmptyResult() {
            ProductSearchRequest request = new ProductSearchRequest(
                    "nonexistent_product_xyz", null, null, null, null, null, null, 0, 20
            );

            SearchHits<ProductDoc> emptyHits = mock(SearchHits.class);
            when(emptyHits.stream()).thenReturn(Stream.empty());
            when(elasticsearchOperations.search(any(CriteriaQuery.class), eq(ProductDoc.class)))
                    .thenReturn(emptyHits);

            List<ProductDoc> results = searchService.searchProducts(request);

            assertThat(results).isEmpty();
        }
    }

    // =========================================================================
    // SearchHit content mapping tests
    // =========================================================================

    @Nested
    @DisplayName("SearchHit content mapping")
    class SearchHitMappingTests {

        @Test
        @DisplayName("Should correctly map SearchHit content to ProductDoc list")
        void shouldMapSearchHitToProductDoc() {
            ProductSearchRequest request = new ProductSearchRequest(
                    "phone", null, null, null, null, null, null, 0, 20
            );

            ProductDoc doc1 = createProductDoc(6001L, "Pixel 8", 699.0,
                    "Google", CategoryCode.SMARTPHONE, ProductStatus.ACTIVE);
            ProductDoc doc2 = createProductDoc(6002L, "iPhone 16", 999.0,
                    "Apple", CategoryCode.SMARTPHONE, ProductStatus.ACTIVE);

            SearchHit<ProductDoc> hit1 = mock(SearchHit.class);
            when(hit1.getContent()).thenReturn(doc1);

            SearchHit<ProductDoc> hit2 = mock(SearchHit.class);
            when(hit2.getContent()).thenReturn(doc2);

            SearchHits<ProductDoc> searchHits = mock(SearchHits.class);
            when(searchHits.stream()).thenReturn(Stream.of(hit1, hit2));
            when(elasticsearchOperations.search(any(CriteriaQuery.class), eq(ProductDoc.class)))
                    .thenReturn(searchHits);

            List<ProductDoc> results = searchService.searchProducts(request);

            assertThat(results).hasSize(2);

            assertThat(results.get(0).getProductCode()).isEqualTo(6001L);
            assertThat(results.get(0).getProductName()).isEqualTo("Pixel 8");
            assertThat(results.get(0).getPrice()).isEqualTo(699.0);
            assertThat(results.get(0).getBrand()).isEqualTo("Google");
            assertThat(results.get(0).getCategoryCode()).isEqualTo(CategoryCode.SMARTPHONE);
            assertThat(results.get(0).getStatus()).isEqualTo(ProductStatus.ACTIVE);

            assertThat(results.get(1).getProductCode()).isEqualTo(6002L);
            assertThat(results.get(1).getProductName()).isEqualTo("iPhone 16");
            assertThat(results.get(1).getPrice()).isEqualTo(999.0);
            assertThat(results.get(1).getBrand()).isEqualTo("Apple");
            assertThat(results.get(1).getCategoryCode()).isEqualTo(CategoryCode.SMARTPHONE);
            assertThat(results.get(1).getStatus()).isEqualTo(ProductStatus.ACTIVE);

            verify(elasticsearchOperations).search(any(CriteriaQuery.class), eq(ProductDoc.class));
        }
    }

}