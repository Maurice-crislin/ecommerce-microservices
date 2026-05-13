package org.example.productservice;

import org.example.productservice.cache.ProductBloomFilter;
import org.example.productservice.dto.*;
import org.example.productservice.entity.Product;
import org.example.productservice.enums.ProductStatus;
import org.example.productservice.repository.ProductRepository;
import org.example.productservice.service.impl.ProductServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    private ProductServiceImpl productService;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private ProductBloomFilter productBloomFilter;

    private static final Long PRODUCT_CODE_1 = 10010001L;
    private static final Long PRODUCT_CODE_2 = 10010002L;
    private static final Long PRODUCT_CODE_3 = 10010003L;
    private static final Long NON_EXISTENT_CODE = 99999999L;
    private static final Long PRODUCT_CODE_INACTIVE = 10010005L;
    private static final String CACHE_KEY_PREFIX = "product:detail:";
    private static final String LOCK_KEY_PREFIX = "product:lock:";
    private static final long CACHE_TTL = 1_800_000L;

    private Product activeProduct;
    private Product activeProduct2;
    private Product inactiveProduct;

    @BeforeEach
    void setUp() {
        productService = new ProductServiceImpl(productRepository, redisTemplate, productBloomFilter);
        ReflectionTestUtils.setField(productService, "cacheTtl", (int) CACHE_TTL);

        activeProduct = new Product();
        activeProduct.setId(1L);
        activeProduct.setProductCode(PRODUCT_CODE_1);
        activeProduct.setProductName("Active Product");
        activeProduct.setPrice(new BigDecimal("199.99"));
        activeProduct.setStatus(ProductStatus.ACTIVE);

        activeProduct2 = new Product();
        activeProduct2.setId(2L);
        activeProduct2.setProductCode(PRODUCT_CODE_2);
        activeProduct2.setProductName("Active Product 2");
        activeProduct2.setPrice(new BigDecimal("99.99"));
        activeProduct2.setStatus(ProductStatus.ACTIVE);

        inactiveProduct = new Product();
        inactiveProduct.setId(3L);
        inactiveProduct.setProductCode(PRODUCT_CODE_INACTIVE);
        inactiveProduct.setProductName("Inactive Product");
        inactiveProduct.setPrice(new BigDecimal("299.99"));
        inactiveProduct.setStatus(ProductStatus.INACTIVE);

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ========================================================================
    // getProductPrice (单商品价格查询) - Core flow
    // ========================================================================
    @Nested
    @DisplayName("getProductPrice - Single product price query")
    class GetProductPriceTests {

        @Test
        @DisplayName("Should return cached product price when Redis cache hits")
        void shouldReturnCachedProductPrice() {
            when(productBloomFilter.mightContain(PRODUCT_CODE_1)).thenReturn(true);

            Product cachedProduct = new Product();
            cachedProduct.setProductCode(PRODUCT_CODE_1);
            cachedProduct.setPrice(new BigDecimal("199.99"));
            cachedProduct.setStatus(ProductStatus.ACTIVE);

            when(valueOperations.get(CACHE_KEY_PREFIX + PRODUCT_CODE_1)).thenReturn(cachedProduct);

            ProductPriceResponse response = productService.getProductPrice(PRODUCT_CODE_1);

            assertThat(response).isNotNull();
            assertThat(response.getProductCode()).isEqualTo(PRODUCT_CODE_1);
            assertThat(response.getPrice()).isEqualByComparingTo(new BigDecimal("199.99"));
            assertThat(response.getStatus()).isEqualTo(ProductStatus.ACTIVE);

            verify(valueOperations).get(CACHE_KEY_PREFIX + PRODUCT_CODE_1);
            verify(productRepository, never()).findProductByProductCode(anyLong());
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when bloom filter says product does NOT exist")
        void shouldThrowWhenBloomFilterSaysNotExist() {
            when(productBloomFilter.mightContain(NON_EXISTENT_CODE)).thenReturn(false);

            assertThatThrownBy(() -> productService.getProductPrice(NON_EXISTENT_CODE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");

            verify(productBloomFilter).mightContain(NON_EXISTENT_CODE);
            verifyNoInteractions(valueOperations);
            verifyNoInteractions(productRepository);
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when productCode is null")
        void shouldThrowWhenProductCodeIsNull() {
            assertThatThrownBy(() -> productService.getProductPrice(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Product code is null");
        }

        @Test
        @DisplayName("Should query DB and cache result when cache misses and lock acquired (double-check pattern)")
        void shouldQueryDbAndCacheWhenCacheMiss() {
            when(productBloomFilter.mightContain(PRODUCT_CODE_1)).thenReturn(true);
            // Double-check: first get (miss), second get after lock (miss), then DB
            when(valueOperations.get(CACHE_KEY_PREFIX + PRODUCT_CODE_1))
                    .thenReturn(null)   // first read: cache miss
                    .thenReturn(null);  // double-check after lock: still miss
            when(valueOperations.setIfAbsent(eq(LOCK_KEY_PREFIX + PRODUCT_CODE_1), eq("1"), eq(20L), eq(TimeUnit.SECONDS)))
                    .thenReturn(true);
            when(productRepository.findProductByProductCode(PRODUCT_CODE_1)).thenReturn(Optional.of(activeProduct));

            ProductPriceResponse response = productService.getProductPrice(PRODUCT_CODE_1);

            assertThat(response).isNotNull();
            assertThat(response.getProductCode()).isEqualTo(PRODUCT_CODE_1);
            assertThat(response.getPrice()).isEqualByComparingTo(new BigDecimal("199.99"));
            assertThat(response.getStatus()).isEqualTo(ProductStatus.ACTIVE);

            verify(valueOperations, times(2)).get(CACHE_KEY_PREFIX + PRODUCT_CODE_1);
            verify(valueOperations).setIfAbsent(eq(LOCK_KEY_PREFIX + PRODUCT_CODE_1), eq("1"), eq(20L), eq(TimeUnit.SECONDS));
            verify(productRepository).findProductByProductCode(PRODUCT_CODE_1);
            verify(valueOperations).set(eq(CACHE_KEY_PREFIX + PRODUCT_CODE_1), eq(activeProduct), eq(CACHE_TTL), eq(TimeUnit.MILLISECONDS));
            verify(redisTemplate).delete(LOCK_KEY_PREFIX + PRODUCT_CODE_1);
        }

        @Test
        @DisplayName("Should write null placeholder and throw when product not found in DB")
        void shouldWriteNullPlaceholderWhenProductNotInDb() {
            when(productBloomFilter.mightContain(NON_EXISTENT_CODE)).thenReturn(true);
            when(valueOperations.get(CACHE_KEY_PREFIX + NON_EXISTENT_CODE))
                    .thenReturn(null)   // first read: miss
                    .thenReturn(null);  // double-check: still miss
            when(valueOperations.setIfAbsent(eq(LOCK_KEY_PREFIX + NON_EXISTENT_CODE), eq("1"), eq(20L), eq(TimeUnit.SECONDS)))
                    .thenReturn(true);
            when(productRepository.findProductByProductCode(NON_EXISTENT_CODE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.getProductPrice(NON_EXISTENT_CODE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");

            // Verify null placeholder was written with 5 MINUTES TTL
            ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
            verify(valueOperations).set(eq(CACHE_KEY_PREFIX + NON_EXISTENT_CODE), productCaptor.capture(), eq(5L), eq(TimeUnit.MINUTES));
            assertThat(productCaptor.getValue().getProductCode()).isNull(); // empty placeholder

            verify(redisTemplate).delete(LOCK_KEY_PREFIX + NON_EXISTENT_CODE);
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when null-placeholder is detected in cache")
        void shouldThrowWhenNullPlaceholderDetected() {
            when(productBloomFilter.mightContain(NON_EXISTENT_CODE)).thenReturn(true);
            Product nullPlaceholder = new Product();
            when(valueOperations.get(CACHE_KEY_PREFIX + NON_EXISTENT_CODE)).thenReturn(nullPlaceholder);

            assertThatThrownBy(() -> productService.getProductPrice(NON_EXISTENT_CODE))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(valueOperations).get(CACHE_KEY_PREFIX + NON_EXISTENT_CODE);
            verify(productRepository, never()).findProductByProductCode(anyLong());
        }

        @Test
        @DisplayName("Should retry when lock not acquired and eventually succeed via double-check")
        void shouldRetryWhenLockNotAcquiredAndSucceed() {
            when(productBloomFilter.mightContain(PRODUCT_CODE_1)).thenReturn(true);
            // Two get calls before lock (first call misses), then third get for double-check
            when(valueOperations.get(CACHE_KEY_PREFIX + PRODUCT_CODE_1))
                    .thenReturn(null)             // first read: miss
                    .thenReturn(null)             // retry: still miss
                    .thenReturn(activeProduct);   // double-check after lock: hit!

            when(valueOperations.setIfAbsent(eq(LOCK_KEY_PREFIX + PRODUCT_CODE_1), eq("1"), eq(20L), eq(TimeUnit.SECONDS)))
                    .thenReturn(false)  // lock fail first time
                    .thenReturn(true);  // lock succeed second time

            ProductPriceResponse response = productService.getProductPrice(PRODUCT_CODE_1);

            assertThat(response).isNotNull();
            assertThat(response.getProductCode()).isEqualTo(PRODUCT_CODE_1);

            verify(valueOperations, atLeast(2)).setIfAbsent(
                    eq(LOCK_KEY_PREFIX + PRODUCT_CODE_1), eq("1"), eq(20L), eq(TimeUnit.SECONDS));
            verify(productRepository, never()).findProductByProductCode(anyLong()); // resolved from double-check
            verify(redisTemplate).delete(LOCK_KEY_PREFIX + PRODUCT_CODE_1);
        }

        @Test
        @DisplayName("Should retry when lock not acquired and fallback to DB, cache result")
        void shouldRetryLockThenQueryDb() {
            when(productBloomFilter.mightContain(PRODUCT_CODE_1)).thenReturn(true);
            when(valueOperations.get(CACHE_KEY_PREFIX + PRODUCT_CODE_1))
                    .thenReturn(null)   // first read: miss
                    .thenReturn(null)   // retry: miss (then lock succeed, then double-check)
                    .thenReturn(null);  // double-check after lock: still miss

            when(valueOperations.setIfAbsent(eq(LOCK_KEY_PREFIX + PRODUCT_CODE_1), eq("1"), eq(20L), eq(TimeUnit.SECONDS)))
                    .thenReturn(false)  // lock fail
                    .thenReturn(true);  // lock succeed (retry)

            when(productRepository.findProductByProductCode(PRODUCT_CODE_1)).thenReturn(Optional.of(activeProduct));

            ProductPriceResponse response = productService.getProductPrice(PRODUCT_CODE_1);

            assertThat(response).isNotNull();
            assertThat(response.getProductCode()).isEqualTo(PRODUCT_CODE_1);

            verify(productRepository).findProductByProductCode(PRODUCT_CODE_1);
            verify(valueOperations).set(eq(CACHE_KEY_PREFIX + PRODUCT_CODE_1), eq(activeProduct), eq(CACHE_TTL), eq(TimeUnit.MILLISECONDS));
            verify(redisTemplate).delete(LOCK_KEY_PREFIX + PRODUCT_CODE_1);
        }

        @Test
        @DisplayName("Should throw RuntimeException when max retries exhausted")
        void shouldThrowWhenMaxRetriesExhausted() {
            when(productBloomFilter.mightContain(PRODUCT_CODE_1)).thenReturn(true);
            when(valueOperations.get(CACHE_KEY_PREFIX + PRODUCT_CODE_1)).thenReturn(null);
            when(valueOperations.setIfAbsent(eq(LOCK_KEY_PREFIX + PRODUCT_CODE_1), eq("1"), eq(20L), eq(TimeUnit.SECONDS)))
                    .thenReturn(false);

            assertThatThrownBy(() -> productService.getProductPrice(PRODUCT_CODE_1))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to acquire lock");

            verify(valueOperations, atLeast(5)).setIfAbsent(
                    eq(LOCK_KEY_PREFIX + PRODUCT_CODE_1), eq("1"), eq(20L), eq(TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Should handle Redis read failure gracefully and fallback to DB")
        void shouldHandleRedisReadFailure() {
            when(productBloomFilter.mightContain(PRODUCT_CODE_1)).thenReturn(true);
            // tryGetProductFromRedis catches exception and returns null
            when(valueOperations.get(CACHE_KEY_PREFIX + PRODUCT_CODE_1))
                    .thenThrow(new RuntimeException("Redis connection error"))
                    .thenReturn(null);
            when(valueOperations.setIfAbsent(eq(LOCK_KEY_PREFIX + PRODUCT_CODE_1), eq("1"), eq(20L), eq(TimeUnit.SECONDS)))
                    .thenReturn(true);
            when(productRepository.findProductByProductCode(PRODUCT_CODE_1)).thenReturn(Optional.of(activeProduct));

            ProductPriceResponse response = productService.getProductPrice(PRODUCT_CODE_1);

            assertThat(response).isNotNull();
            assertThat(response.getProductCode()).isEqualTo(PRODUCT_CODE_1);
            assertThat(response.getPrice()).isEqualByComparingTo(new BigDecimal("199.99"));
            verify(productRepository).findProductByProductCode(PRODUCT_CODE_1);
        }

        @Test
        @DisplayName("Should double-check Redis after acquiring lock and return if found")
        void shouldDoubleCheckRedisAfterLock() {
            when(productBloomFilter.mightContain(PRODUCT_CODE_1)).thenReturn(true);
            when(valueOperations.get(CACHE_KEY_PREFIX + PRODUCT_CODE_1))
                    .thenReturn(null)             // first read: miss
                    .thenReturn(activeProduct);   // double-check after lock: hit

            when(valueOperations.setIfAbsent(eq(LOCK_KEY_PREFIX + PRODUCT_CODE_1), eq("1"), eq(20L), eq(TimeUnit.SECONDS)))
                    .thenReturn(true);

            ProductPriceResponse response = productService.getProductPrice(PRODUCT_CODE_1);

            assertThat(response).isNotNull();
            assertThat(response.getProductCode()).isEqualTo(PRODUCT_CODE_1);
            assertThat(response.getPrice()).isEqualByComparingTo(new BigDecimal("199.99"));

            verify(productRepository, never()).findProductByProductCode(anyLong());
            verify(redisTemplate).delete(LOCK_KEY_PREFIX + PRODUCT_CODE_1);
        }
    }

    // ========================================================================
    // getProductPrices (批量查询 - internal method)
    // ========================================================================
    @Nested
    @DisplayName("getProductPrices - Batch product prices query")
    class GetProductPricesTests {

        @BeforeEach
        void setUp() {
            lenient().when(productBloomFilter.mightContain(anyLong())).thenReturn(true);
        }

        @Test
        @DisplayName("Should return results from Redis cache when all codes hit")
        void shouldReturnResultsFromRedisCache() {
            List<Long> codes = Arrays.asList(PRODUCT_CODE_1, PRODUCT_CODE_2);

            Product cached1 = new Product();
            cached1.setProductCode(PRODUCT_CODE_1);
            cached1.setPrice(new BigDecimal("199.99"));
            cached1.setStatus(ProductStatus.ACTIVE);

            Product cached2 = new Product();
            cached2.setProductCode(PRODUCT_CODE_2);
            cached2.setPrice(new BigDecimal("99.99"));
            cached2.setStatus(ProductStatus.ACTIVE);

            when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList(cached1, cached2));

            List<ProductPriceResponse> responses = productService.getProductPrices(codes);

            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getProductCode()).isEqualTo(PRODUCT_CODE_1);
            assertThat(responses.get(1).getProductCode()).isEqualTo(PRODUCT_CODE_2);

            verify(valueOperations).multiGet(anyList());
            verify(productRepository, never()).findProductsByProductCodeIn(anyList());
        }

        @Test
        @DisplayName("Should query DB for cache-missed products and backfill cache")
        void shouldQueryDbForCacheMisses() {
            List<Long> codes = Arrays.asList(PRODUCT_CODE_1, PRODUCT_CODE_2);

            Product cached1 = new Product();
            cached1.setProductCode(PRODUCT_CODE_1);
            cached1.setPrice(new BigDecimal("199.99"));
            cached1.setStatus(ProductStatus.ACTIVE);

            // Some codes in cache, some miss
            when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList(cached1, null));
            when(productRepository.findProductsByProductCodeIn(Arrays.asList(PRODUCT_CODE_2)))
                    .thenReturn(Arrays.asList(activeProduct2));

            List<ProductPriceResponse> responses = productService.getProductPrices(codes);

            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getProductCode()).isEqualTo(PRODUCT_CODE_1);
            assertThat(responses.get(1).getProductCode()).isEqualTo(PRODUCT_CODE_2);

            verify(valueOperations).set(eq(CACHE_KEY_PREFIX + PRODUCT_CODE_2), any(Product.class), eq(CACHE_TTL), eq(TimeUnit.MILLISECONDS));
        }

        @Test
        @DisplayName("Should write null placeholder for product missing from both cache and DB")
        void shouldWriteNullPlaceholderForMissingProduct() {
            List<Long> codes = Arrays.asList(PRODUCT_CODE_1, NON_EXISTENT_CODE);

            Product cached1 = new Product();
            cached1.setProductCode(PRODUCT_CODE_1);
            cached1.setPrice(new BigDecimal("199.99"));
            cached1.setStatus(ProductStatus.ACTIVE);

            when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList(cached1, null));
            when(productRepository.findProductsByProductCodeIn(Arrays.asList(NON_EXISTENT_CODE)))
                    .thenReturn(Collections.emptyList());

            List<ProductPriceResponse> responses = productService.getProductPrices(codes);

            assertThat(responses).hasSize(1);
            assertThat(responses.get(0).getProductCode()).isEqualTo(PRODUCT_CODE_1);

            ArgumentCaptor<Product> placeholderCaptor = ArgumentCaptor.forClass(Product.class);
            verify(valueOperations).set(eq(CACHE_KEY_PREFIX + NON_EXISTENT_CODE), placeholderCaptor.capture(), eq(5L), eq(TimeUnit.MINUTES));
            assertThat(placeholderCaptor.getValue().getProductCode()).isNull();
        }

        @Test
        @DisplayName("Should handle empty code list")
        void shouldHandleEmptyCodeList() {
            List<ProductPriceResponse> responses = productService.getProductPrices(Collections.emptyList());

            assertThat(responses).isEmpty();
        }

        @Test
        @DisplayName("Should filter out products that bloom filter says don't exist")
        void shouldFilterByBloomFilter() {
            when(productBloomFilter.mightContain(PRODUCT_CODE_1)).thenReturn(true);
            when(productBloomFilter.mightContain(PRODUCT_CODE_2)).thenReturn(false);

            List<Long> codes = Arrays.asList(PRODUCT_CODE_1, PRODUCT_CODE_2);

            Product cached1 = new Product();
            cached1.setProductCode(PRODUCT_CODE_1);
            cached1.setPrice(new BigDecimal("199.99"));
            cached1.setStatus(ProductStatus.ACTIVE);

            when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList(cached1));

            List<ProductPriceResponse> responses = productService.getProductPrices(codes);

            assertThat(responses).hasSize(1);
            assertThat(responses.get(0).getProductCode()).isEqualTo(PRODUCT_CODE_1);
        }

        @Test
        @DisplayName("Should return empty list when all codes filtered by bloom filter")
        void shouldReturnEmptyWhenAllFiltered() {
            when(productBloomFilter.mightContain(anyLong())).thenReturn(false);

            List<ProductPriceResponse> responses = productService.getProductPrices(
                    Arrays.asList(PRODUCT_CODE_1, PRODUCT_CODE_2));

            assertThat(responses).isEmpty();
        }

        @Test
        @DisplayName("Should skip null placeholder in cache results")
        void shouldSkipNullPlaceholderInCache() {
            List<Long> codes = Arrays.asList(PRODUCT_CODE_1, NON_EXISTENT_CODE);

            Product cached1 = new Product();
            cached1.setProductCode(PRODUCT_CODE_1);
            cached1.setPrice(new BigDecimal("199.99"));
            cached1.setStatus(ProductStatus.ACTIVE);

            when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList(cached1, new Product()));

            List<ProductPriceResponse> responses = productService.getProductPrices(codes);

            assertThat(responses).hasSize(1);
            assertThat(responses.get(0).getProductCode()).isEqualTo(PRODUCT_CODE_1);
            verify(productRepository, never()).findProductsByProductCodeIn(anyList());
        }

        @Test
        @DisplayName("Should deduplicate codes before querying")
        void shouldDeduplicateCodes() {
            List<Long> codes = Arrays.asList(PRODUCT_CODE_1, PRODUCT_CODE_1);

            Product cached1 = new Product();
            cached1.setProductCode(PRODUCT_CODE_1);
            cached1.setPrice(new BigDecimal("199.99"));
            cached1.setStatus(ProductStatus.ACTIVE);

            when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList(cached1));

            List<ProductPriceResponse> responses = productService.getProductPrices(codes);

            assertThat(responses).hasSize(1);
        }
    }

    // ========================================================================
    // getBatchProductPrices (增强批量查询)
    // ========================================================================
    @Nested
    @DisplayName("getBatchProductPrices - Enhanced batch query")
    class GetBatchProductPricesTests {

        @BeforeEach
        void setUp() {
            lenient().when(productBloomFilter.mightContain(anyLong())).thenReturn(true);
        }

        @Test
        @DisplayName("Should return allProductsOrderable=true when all products are ACTIVE")
        void shouldReturnAllOrderableWhenAllActive() {
            List<Long> codes = Arrays.asList(PRODUCT_CODE_1, PRODUCT_CODE_2);

            Product cached1 = new Product();
            cached1.setProductCode(PRODUCT_CODE_1);
            cached1.setPrice(new BigDecimal("199.99"));
            cached1.setStatus(ProductStatus.ACTIVE);

            Product cached2 = new Product();
            cached2.setProductCode(PRODUCT_CODE_2);
            cached2.setPrice(new BigDecimal("99.99"));
            cached2.setStatus(ProductStatus.ACTIVE);

            when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList(cached1, cached2));

            BatchProductPriceResponse response = productService.getBatchProductPrices(codes);

            assertThat(response.isAllProductsOrderable()).isTrue();
            assertThat(response.getProducts()).hasSize(2);
            assertThat(response.getMissingProductCodes()).isEmpty();
        }

        @Test
        @DisplayName("Should return allProductsOrderable=false when any product is INACTIVE")
        void shouldReturnNotOrderableWhenInactiveExists() {
            List<Long> codes = Arrays.asList(PRODUCT_CODE_1, PRODUCT_CODE_INACTIVE);

            Product cached1 = new Product();
            cached1.setProductCode(PRODUCT_CODE_1);
            cached1.setPrice(new BigDecimal("199.99"));
            cached1.setStatus(ProductStatus.ACTIVE);

            Product cached2 = new Product();
            cached2.setProductCode(PRODUCT_CODE_INACTIVE);
            cached2.setPrice(new BigDecimal("299.99"));
            cached2.setStatus(ProductStatus.INACTIVE);

            when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList(cached1, cached2));

            BatchProductPriceResponse response = productService.getBatchProductPrices(codes);

            assertThat(response.isAllProductsOrderable()).isFalse();
            assertThat(response.getProducts()).hasSize(2);
            assertThat(response.getMissingProductCodes()).isEmpty();
        }

        @Test
        @DisplayName("Should detect missing product codes")
        void shouldDetectMissingProductCodes() {
            List<Long> codes = Arrays.asList(PRODUCT_CODE_1, NON_EXISTENT_CODE);

            Product cached1 = new Product();
            cached1.setProductCode(PRODUCT_CODE_1);
            cached1.setPrice(new BigDecimal("199.99"));
            cached1.setStatus(ProductStatus.ACTIVE);

            when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList(cached1, null));
            when(productRepository.findProductsByProductCodeIn(Arrays.asList(NON_EXISTENT_CODE)))
                    .thenReturn(Collections.emptyList());

            BatchProductPriceResponse response = productService.getBatchProductPrices(codes);

            assertThat(response.isAllProductsOrderable()).isFalse();
            assertThat(response.getProducts()).hasSize(1);
            assertThat(response.getProducts().get(0).getProductCode()).isEqualTo(PRODUCT_CODE_1);
            assertThat(response.getMissingProductCodes()).containsExactly(NON_EXISTENT_CODE);
        }

        @Test
        @DisplayName("Should preserve original request order in response")
        void shouldPreserveOriginalOrder() {
            List<Long> codes = Arrays.asList(PRODUCT_CODE_2, PRODUCT_CODE_1, PRODUCT_CODE_3);

            Product cached1 = new Product();
            cached1.setProductCode(PRODUCT_CODE_1);
            cached1.setPrice(new BigDecimal("199.99"));
            cached1.setStatus(ProductStatus.ACTIVE);

            Product cached2 = new Product();
            cached2.setProductCode(PRODUCT_CODE_2);
            cached2.setPrice(new BigDecimal("99.99"));
            cached2.setStatus(ProductStatus.ACTIVE);

            Product cached3 = new Product();
            cached3.setProductCode(PRODUCT_CODE_3);
            cached3.setPrice(new BigDecimal("149.99"));
            cached3.setStatus(ProductStatus.ACTIVE);

            when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList(cached2, cached1, cached3));

            BatchProductPriceResponse response = productService.getBatchProductPrices(codes);

            assertThat(response.getProducts()).hasSize(3);
            assertThat(response.getProducts().get(0).getProductCode()).isEqualTo(PRODUCT_CODE_2);
            assertThat(response.getProducts().get(1).getProductCode()).isEqualTo(PRODUCT_CODE_1);
            assertThat(response.getProducts().get(2).getProductCode()).isEqualTo(PRODUCT_CODE_3);
        }

        @Test
        @DisplayName("Should return not orderable when products missing")
        void shouldReturnNotOrderableWhenMissing() {
            List<Long> codes = Arrays.asList(PRODUCT_CODE_1, NON_EXISTENT_CODE);

            Product cached1 = new Product();
            cached1.setProductCode(PRODUCT_CODE_1);
            cached1.setPrice(new BigDecimal("199.99"));
            cached1.setStatus(ProductStatus.ACTIVE);

            when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList(cached1, null));
            when(productRepository.findProductsByProductCodeIn(Arrays.asList(NON_EXISTENT_CODE)))
                    .thenReturn(Collections.emptyList());

            BatchProductPriceResponse response = productService.getBatchProductPrices(codes);

            assertThat(response.isAllProductsOrderable()).isFalse();
            assertThat(response.getMissingProductCodes()).containsExactly(NON_EXISTENT_CODE);
        }
    }

    // ========================================================================
    // addProduct (新增商品)
    // ========================================================================
    @Nested
    @DisplayName("addProduct - Create new product")
    class AddProductTests {

        @Test
        @DisplayName("Should create product and return ProductResponse")
        void shouldCreateProduct() {
            ProductCreateRequest request = new ProductCreateRequest();
            request.setProductName("New Product");
            request.setPrice(new BigDecimal("99.99"));

            Product savedProduct = new Product();
            savedProduct.setId(1L);
            savedProduct.setProductCode(PRODUCT_CODE_1);
            savedProduct.setProductName("New Product");
            savedProduct.setPrice(new BigDecimal("99.99"));
            savedProduct.setStatus(ProductStatus.ACTIVE);

            when(productRepository.save(any(Product.class))).thenReturn(savedProduct);

            ProductResponse response = productService.addProduct(request);

            assertThat(response).isNotNull();
            assertThat(response.getProductCode()).isEqualTo(PRODUCT_CODE_1);
            assertThat(response.getProductName()).isEqualTo("New Product");
            assertThat(response.getPrice()).isEqualByComparingTo(new BigDecimal("99.99"));
            assertThat(response.getStatus()).isEqualTo(ProductStatus.ACTIVE);

            ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
            verify(productRepository).save(productCaptor.capture());
            Product captured = productCaptor.getValue();
            assertThat(captured.getProductName()).isEqualTo("New Product");
            assertThat(captured.getPrice()).isEqualByComparingTo(new BigDecimal("99.99"));
        }

        @Test
        @DisplayName("Should create product with null name")
        void shouldCreateProductWithNullName() {
            ProductCreateRequest request = new ProductCreateRequest();
            request.setProductName(null);
            request.setPrice(new BigDecimal("50.00"));

            Product savedProduct = new Product();
            savedProduct.setId(2L);
            savedProduct.setProductCode(PRODUCT_CODE_2);
            savedProduct.setProductName(null);
            savedProduct.setPrice(new BigDecimal("50.00"));
            savedProduct.setStatus(ProductStatus.ACTIVE);

            when(productRepository.save(any(Product.class))).thenReturn(savedProduct);

            ProductResponse response = productService.addProduct(request);

            assertThat(response).isNotNull();
            assertThat(response.getProductName()).isNull();
            assertThat(response.getPrice()).isEqualByComparingTo(new BigDecimal("50.00"));
        }
    }

    // ========================================================================
    // updateProduct (更新商品 - 延迟双删)
    // ========================================================================
    @Nested
    @DisplayName("updateProduct - Update product with delayed double-delete")
    class UpdateProductTests {

        @Test
        @DisplayName("Should update price with cache delayed double-delete")
        void shouldUpdatePriceWithDelayedDoubleDelete() {
            ProductUpdateRequest updateRequest = new ProductUpdateRequest(new BigDecimal("299.99"));

            when(productRepository.findProductByProductCode(PRODUCT_CODE_1)).thenReturn(Optional.of(activeProduct));
            when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ProductResponse response = productService.updateProduct(PRODUCT_CODE_1, updateRequest);

            assertThat(response).isNotNull();
            assertThat(response.getProductCode()).isEqualTo(PRODUCT_CODE_1);
            assertThat(response.getPrice()).isEqualByComparingTo(new BigDecimal("299.99"));

            // Verify delayed double-delete: cache deleted before and after save (2 times)
            verify(redisTemplate, atLeast(2)).delete(CACHE_KEY_PREFIX + PRODUCT_CODE_1);

            ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
            verify(productRepository).save(productCaptor.capture());
            assertThat(productCaptor.getValue().getPrice()).isEqualByComparingTo(new BigDecimal("299.99"));
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when product not found (before any Redis delete)")
        void shouldThrowWhenProductNotFound() {
            ProductUpdateRequest updateRequest = new ProductUpdateRequest(new BigDecimal("100.00"));

            when(productRepository.findProductByProductCode(NON_EXISTENT_CODE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.updateProduct(NON_EXISTENT_CODE, updateRequest))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");

            verify(productRepository, never()).save(any(Product.class));
            // DB lookup happens first; since product not found, redisTemplate.delete() is never called
            verify(redisTemplate, never()).delete(CACHE_KEY_PREFIX + NON_EXISTENT_CODE);
        }

        @Test
        @DisplayName("Should not change price when update request has null price")
        void shouldNotChangePriceWhenUpdateRequestHasNullPrice() {
            ProductUpdateRequest updateRequest = new ProductUpdateRequest(null);

            when(productRepository.findProductByProductCode(PRODUCT_CODE_1)).thenReturn(Optional.of(activeProduct));
            when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ProductResponse response = productService.updateProduct(PRODUCT_CODE_1, updateRequest);

            assertThat(response.getPrice()).isEqualByComparingTo(new BigDecimal("199.99")); // unchanged
        }
    }

    // ========================================================================
    // deleteProduct (删除商品 - 延迟双删)
    // ========================================================================
    @Nested
    @DisplayName("deleteProduct - Delete product with delayed double-delete")
    class DeleteProductTests {

        @Test
        @DisplayName("Should delete product with cache delayed double-delete")
        void shouldDeleteProductWithDelayedDoubleDelete() {
            // Mock: product exists in DB
            when(productRepository.findProductByProductCode(PRODUCT_CODE_1)).thenReturn(Optional.of(activeProduct));

            productService.deleteProduct(PRODUCT_CODE_1);

            verify(redisTemplate, atLeast(2)).delete(CACHE_KEY_PREFIX + PRODUCT_CODE_1);
            verify(productRepository).delete(activeProduct);
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when deleting non-existent product")
        void shouldThrowWhenProductNotFound() {
            // Mock: product not found in DB
            when(productRepository.findProductByProductCode(NON_EXISTENT_CODE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.deleteProduct(NON_EXISTENT_CODE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");

            verify(productRepository, never()).delete(any(Product.class));
            verify(redisTemplate, never()).delete(anyString());
        }
    }

    // ========================================================================
    // Redis failure scenarios (容错场景)
    // ========================================================================
    @Nested
    @DisplayName("Redis failure scenarios")
    class RedisFailureTests {

        @BeforeEach
        void setUp() {
            lenient().when(productBloomFilter.mightContain(anyLong())).thenReturn(true);
        }

        @Test
        @DisplayName("Should propagate exception when first Redis delete fails (code does not catch Redis delete exceptions)")
        void shouldPropagateWhenRedisDeleteFails() {
            ProductUpdateRequest updateRequest = new ProductUpdateRequest(new BigDecimal("399.99"));

            when(productRepository.findProductByProductCode(PRODUCT_CODE_1)).thenReturn(Optional.of(activeProduct));
            // First delete throws exception
            doThrow(new RuntimeException("Redis down")).when(redisTemplate).delete(CACHE_KEY_PREFIX + PRODUCT_CODE_1);

            assertThatThrownBy(() -> productService.updateProduct(PRODUCT_CODE_1, updateRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Redis down");

            // save should NOT be called because exception was thrown before it (first Redis delete)
            verify(productRepository, never()).save(any(Product.class));
        }
    }
}