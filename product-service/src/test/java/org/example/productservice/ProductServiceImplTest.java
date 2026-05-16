package org.example.productservice;

import org.common.product.enums.CategoryCode;
import org.common.product.enums.ProductStatus;
import org.example.productservice.cache.ProductBloomFilter;
import org.example.productservice.dto.*;
import org.example.productservice.entity.Product;
import org.example.productservice.entity.ProductDetail;
import org.example.productservice.mapper.ProductEventMapper;
import org.example.productservice.mq.producer.ProductSyncProducer;
import org.example.productservice.repository.ProductRepository;
import org.example.productservice.service.ProductService;
import org.example.productservice.service.impl.ProductServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProductService 单元测试")
class ProductServiceImplTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private ProductBloomFilter productBloomFilter;

    @Mock
    private ProductEventMapper productEventMapper;

    @Mock
    private ProductSyncProducer productSyncProducer;

    @InjectMocks
    private ProductServiceImpl productService;

    private static final Long EXISTING_PRODUCT_CODE = 10010001L;
    private static final Long NON_EXISTENT_PRODUCT_CODE = 99999999L;
    private static final String CACHE_KEY_PREFIX = "product:detail:";
    private static final String LOCK_KEY_PREFIX = "product:lock:";

    private Product existingProduct;
    private ProductDetail existingDetail;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(productService, "cacheTtl", 1800000);

        // Setup existing product
        existingDetail = new ProductDetail();
        existingDetail.setBrand("TestBrand");
        existingDetail.setCategoryCode(CategoryCode.ELECTRONICS);
        existingDetail.setDescription("Test Description");

        existingProduct = new Product();
        existingProduct.setId(1L);
        existingProduct.setProductCode(EXISTING_PRODUCT_CODE);
        existingProduct.setProductName("Test Product");
        existingProduct.setPrice(new BigDecimal("199.99"));
        existingProduct.setStatus(ProductStatus.ACTIVE);
        existingProduct.setProductDetail(existingDetail);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ========================================================================
    // getProductPrice 测试
    // ========================================================================
    @Nested
    @DisplayName("getProductPrice 方法测试")
    class GetProductPriceTests {

        @Test
        @DisplayName("应该抛出异常当 productCode 为 null")
        void shouldThrowExceptionWhenProductCodeIsNull() {
            assertThatThrownBy(() -> productService.getProductPrice(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Product code is null");
        }

        @Test
        @DisplayName("应该抛出异常当布隆过滤器判定商品不存在")
        void shouldThrowExceptionWhenBloomFilterSaysNotExist() {
            when(productBloomFilter.mightContain(NON_EXISTENT_PRODUCT_CODE)).thenReturn(false);

            assertThatThrownBy(() -> productService.getProductPrice(NON_EXISTENT_PRODUCT_CODE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Product not found:" + NON_EXISTENT_PRODUCT_CODE);
        }

        @Test
        @DisplayName("应该从缓存命中并返回商品价格")
        void shouldReturnFromCacheWhenHit() {
            when(productBloomFilter.mightContain(EXISTING_PRODUCT_CODE)).thenReturn(true);
            when(valueOperations.get(CACHE_KEY_PREFIX + EXISTING_PRODUCT_CODE)).thenReturn(existingProduct);

            ProductPriceResponse response = productService.getProductPrice(EXISTING_PRODUCT_CODE);

            assertThat(response).isNotNull();
            assertThat(response.getProductCode()).isEqualTo(EXISTING_PRODUCT_CODE);
            assertThat(response.getPrice()).isEqualTo(new BigDecimal("199.99"));
            assertThat(response.getStatus()).isEqualTo(ProductStatus.ACTIVE);

            verify(productRepository, never()).findProductByProductCode(any());
        }

        @Test
        @DisplayName("应该从数据库查询并缓存当缓存未命中")
        void shouldQueryDatabaseAndCacheWhenCacheMiss() {
            String cacheKey = CACHE_KEY_PREFIX + EXISTING_PRODUCT_CODE;
            String lockKey = LOCK_KEY_PREFIX + EXISTING_PRODUCT_CODE;

            when(productBloomFilter.mightContain(EXISTING_PRODUCT_CODE)).thenReturn(true);
            when(valueOperations.get(cacheKey)).thenReturn(null);
            when(valueOperations.setIfAbsent(eq(lockKey), eq("1"), eq(20L), eq(TimeUnit.SECONDS)))
                    .thenReturn(true);
            when(productRepository.findProductByProductCode(EXISTING_PRODUCT_CODE))
                    .thenReturn(Optional.of(existingProduct));

            ProductPriceResponse response = productService.getProductPrice(EXISTING_PRODUCT_CODE);

            assertThat(response).isNotNull();
            assertThat(response.getProductCode()).isEqualTo(EXISTING_PRODUCT_CODE);

            verify(valueOperations).set(eq(cacheKey), eq(existingProduct), eq(1800000L), eq(TimeUnit.MILLISECONDS));
            // delete 是 RedisTemplate 的方法，不是 ValueOperations 的方法
            verify(redisTemplate).delete(lockKey);
        }

        @Test
        @DisplayName("应该抛出异常当数据库查询不到商品")
        void shouldThrowExceptionWhenProductNotFoundInDatabase() {
            String cacheKey = CACHE_KEY_PREFIX + NON_EXISTENT_PRODUCT_CODE;
            String lockKey = LOCK_KEY_PREFIX + NON_EXISTENT_PRODUCT_CODE;

            when(productBloomFilter.mightContain(NON_EXISTENT_PRODUCT_CODE)).thenReturn(true);
            when(valueOperations.get(cacheKey)).thenReturn(null);
            when(valueOperations.setIfAbsent(eq(lockKey), eq("1"), eq(20L), eq(TimeUnit.SECONDS)))
                    .thenReturn(true);
            when(productRepository.findProductByProductCode(NON_EXISTENT_PRODUCT_CODE))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.getProductPrice(NON_EXISTENT_PRODUCT_CODE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Product not found: " + NON_EXISTENT_PRODUCT_CODE);

            verify(valueOperations).set(eq(cacheKey), any(Product.class), eq(5L), eq(TimeUnit.MINUTES));
            verify(redisTemplate).delete(lockKey);
        }

        @Test
        @DisplayName("应该处理缓存中的空占位对象并抛出异常")
        void shouldHandleEmptyPlaceholderInCache() {
            Product emptyProduct = new Product(); // productCode is null
            String cacheKey = CACHE_KEY_PREFIX + EXISTING_PRODUCT_CODE;

            when(productBloomFilter.mightContain(EXISTING_PRODUCT_CODE)).thenReturn(true);
            when(valueOperations.get(cacheKey)).thenReturn(emptyProduct);

            assertThatThrownBy(() -> productService.getProductPrice(EXISTING_PRODUCT_CODE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Product not found");
        }

        @Test
        @DisplayName("应该重试获取锁当锁被占用时")
        void shouldRetryWhenLockIsHeld() {
            String cacheKey = CACHE_KEY_PREFIX + EXISTING_PRODUCT_CODE;
            String lockKey = LOCK_KEY_PREFIX + EXISTING_PRODUCT_CODE;

            when(productBloomFilter.mightContain(EXISTING_PRODUCT_CODE)).thenReturn(true);
            when(valueOperations.get(cacheKey)).thenReturn(null);
            // First attempt: lock is held by another thread
            when(valueOperations.setIfAbsent(eq(lockKey), eq("1"), eq(20L), eq(TimeUnit.SECONDS)))
                    .thenReturn(false)
                    .thenReturn(true); // Second attempt: acquire lock successfully
            when(productRepository.findProductByProductCode(EXISTING_PRODUCT_CODE))
                    .thenReturn(Optional.of(existingProduct));

            ProductPriceResponse response = productService.getProductPrice(EXISTING_PRODUCT_CODE);

            assertThat(response).isNotNull();
            verify(valueOperations, times(2)).setIfAbsent(eq(lockKey), eq("1"), eq(20L), eq(TimeUnit.SECONDS));
            verify(redisTemplate).delete(lockKey);
        }
    }

    // ========================================================================
    // getProductPrices (批量基础查询) 测试
    // ========================================================================
    @Nested
    @DisplayName("getProductPrices 方法测试")
    class GetProductPricesTests {

        @Test
        @DisplayName("应该返回空列表当输入为空")
        void shouldReturnEmptyWhenInputIsEmpty() {
            List<ProductPriceResponse> result = productService.getProductPrices(Collections.emptyList());

            assertThat(result).isEmpty();
            verify(productRepository, never()).findProductsByProductCodeIn(any());
        }

        @Test
        @DisplayName("应该过滤布隆过滤器判定不存在的商品")
        void shouldFilterProductsNotInBloomFilter() {
            List<Long> inputCodes = Arrays.asList(EXISTING_PRODUCT_CODE, NON_EXISTENT_PRODUCT_CODE);

            when(productBloomFilter.mightContain(EXISTING_PRODUCT_CODE)).thenReturn(true);
            when(productBloomFilter.mightContain(NON_EXISTENT_PRODUCT_CODE)).thenReturn(false);

            when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList(null, null));
            when(productRepository.findProductsByProductCodeIn(anyList()))
                    .thenReturn(Collections.singletonList(existingProduct));

            List<ProductPriceResponse> result = productService.getProductPrices(inputCodes);

            // Only existing product should be returned
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getProductCode()).isEqualTo(EXISTING_PRODUCT_CODE);

            ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
            verify(productRepository).findProductsByProductCodeIn(captor.capture());
            assertThat(captor.getValue()).containsExactly(EXISTING_PRODUCT_CODE);
        }

        @Test
        @DisplayName("应该从批量缓存读取命中的商品")
        void shouldGetHitProductsFromBatchCache() {
            List<Long> inputCodes = Collections.singletonList(EXISTING_PRODUCT_CODE);

            when(productBloomFilter.mightContain(EXISTING_PRODUCT_CODE)).thenReturn(true);
            when(valueOperations.multiGet(anyList())).thenReturn(Collections.singletonList(existingProduct));

            List<ProductPriceResponse> result = productService.getProductPrices(inputCodes);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getProductCode()).isEqualTo(EXISTING_PRODUCT_CODE);

            verify(productRepository, never()).findProductsByProductCodeIn(any());
        }

        @Test
        @DisplayName("应该从数据库查询缓存未命中的商品并回写缓存")
        void shouldQueryDatabaseForMissAndWriteBack() {
            List<Long> inputCodes = Collections.singletonList(EXISTING_PRODUCT_CODE);

            when(productBloomFilter.mightContain(EXISTING_PRODUCT_CODE)).thenReturn(true);
            when(valueOperations.multiGet(anyList())).thenReturn(Collections.singletonList(null));
            when(productRepository.findProductsByProductCodeIn(anyList()))
                    .thenReturn(Collections.singletonList(existingProduct));

            List<ProductPriceResponse> result = productService.getProductPrices(inputCodes);

            assertThat(result).hasSize(1);
            // set 是 ValueOperations 的方法
            verify(valueOperations).set(eq(CACHE_KEY_PREFIX + EXISTING_PRODUCT_CODE), eq(existingProduct), eq(1800000L), eq(TimeUnit.MILLISECONDS));
        }

        @Test
        @DisplayName("应该为数据库中不存在的商品写入空占位对象")
        void shouldWriteEmptyPlaceholderForMissingProducts() {
            List<Long> inputCodes = Collections.singletonList(NON_EXISTENT_PRODUCT_CODE);

            when(productBloomFilter.mightContain(NON_EXISTENT_PRODUCT_CODE)).thenReturn(true);
            when(valueOperations.multiGet(anyList())).thenReturn(Collections.singletonList(null));
            when(productRepository.findProductsByProductCodeIn(anyList()))
                    .thenReturn(Collections.emptyList());

            List<ProductPriceResponse> result = productService.getProductPrices(inputCodes);

            assertThat(result).isEmpty();
            verify(valueOperations).set(eq(CACHE_KEY_PREFIX + NON_EXISTENT_PRODUCT_CODE), any(Product.class), eq(5L), eq(TimeUnit.MINUTES));
        }

        @Test
        @DisplayName("应该跳过缓存中的空占位对象（不查 DB，空占位表示 DB 可确认无数据）")
        void shouldSkipEmptyPlaceholderInCache() {
            Product emptyProduct = new Product(); // productCode is null
            List<Long> inputCodes = Collections.singletonList(EXISTING_PRODUCT_CODE);

            when(productBloomFilter.mightContain(EXISTING_PRODUCT_CODE)).thenReturn(true);
            when(valueOperations.multiGet(anyList())).thenReturn(Collections.singletonList(emptyProduct));

            List<ProductPriceResponse> result = productService.getProductPrices(inputCodes);

            assertThat(result).isEmpty();
            // Empty placeholder in cache means DB was checked before and product doesn't exist.
            // Therefore NO DB call should be made.
            verify(productRepository, never()).findProductsByProductCodeIn(anyList());
        }

        @Test
        @DisplayName("应该去重输入的商品编码")
        void shouldDeduplicateInputCodes() {
            List<Long> inputCodes = Arrays.asList(EXISTING_PRODUCT_CODE, EXISTING_PRODUCT_CODE);

            when(productBloomFilter.mightContain(EXISTING_PRODUCT_CODE)).thenReturn(true);
            when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList(null, null));
            when(productRepository.findProductsByProductCodeIn(anyList()))
                    .thenReturn(Collections.singletonList(existingProduct));

            List<ProductPriceResponse> result = productService.getProductPrices(inputCodes);

            assertThat(result).hasSize(1);
        }
    }

    // ========================================================================
    // getBatchProductPrices (业务增强方法) 测试
    // ========================================================================
    @Nested
    @DisplayName("getBatchProductPrices 方法测试")
    class GetBatchProductPricesTests {

        @Test
        @DisplayName("应该返回正确的批量响应当所有商品都存在且可下单")
        void shouldReturnCorrectResponseWhenAllProductsExistAndOrderable() {
            List<Long> inputCodes = Arrays.asList(EXISTING_PRODUCT_CODE, 10010002L);

            Product product2 = new Product();
            product2.setProductCode(10010002L);
            product2.setPrice(new BigDecimal("99.99"));
            product2.setStatus(ProductStatus.ACTIVE);

            when(productBloomFilter.mightContain(EXISTING_PRODUCT_CODE)).thenReturn(true);
            when(productBloomFilter.mightContain(10010002L)).thenReturn(true);
            when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList(null, null));
            when(productRepository.findProductsByProductCodeIn(anyList()))
                    .thenReturn(Arrays.asList(existingProduct, product2));

            BatchProductPriceResponse response = productService.getBatchProductPrices(inputCodes);

            assertThat(response.isAllProductsOrderable()).isTrue();
            assertThat(response.getProducts()).hasSize(2);
            assertThat(response.getMissingProductCodes()).isEmpty();
        }

        @Test
        @DisplayName("应该正确识别缺失的商品编码")
        void shouldIdentifyMissingProductCodes() {
            List<Long> inputCodes = Arrays.asList(EXISTING_PRODUCT_CODE, NON_EXISTENT_PRODUCT_CODE);

            when(productBloomFilter.mightContain(EXISTING_PRODUCT_CODE)).thenReturn(true);
            when(productBloomFilter.mightContain(NON_EXISTENT_PRODUCT_CODE)).thenReturn(true);
            when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList(null, null));
            when(productRepository.findProductsByProductCodeIn(anyList()))
                    .thenReturn(Collections.singletonList(existingProduct));

            BatchProductPriceResponse response = productService.getBatchProductPrices(inputCodes);

            assertThat(response.isAllProductsOrderable()).isFalse();
            assertThat(response.getProducts()).hasSize(1);
            assertThat(response.getProducts().get(0).getProductCode()).isEqualTo(EXISTING_PRODUCT_CODE);
            assertThat(response.getMissingProductCodes()).containsExactly(NON_EXISTENT_PRODUCT_CODE);
        }

        @Test
        @DisplayName("应该正确识别不可下单的商品（状态非 ACTIVE）")
        void shouldIdentifyNonOrderableProducts() {
            List<Long> inputCodes = Collections.singletonList(EXISTING_PRODUCT_CODE);

            Product inactiveProduct = new Product();
            inactiveProduct.setProductCode(EXISTING_PRODUCT_CODE);
            inactiveProduct.setPrice(new BigDecimal("199.99"));
            inactiveProduct.setStatus(ProductStatus.INACTIVE);

            when(productBloomFilter.mightContain(EXISTING_PRODUCT_CODE)).thenReturn(true);
            when(valueOperations.multiGet(anyList())).thenReturn(Collections.singletonList(null));
            when(productRepository.findProductsByProductCodeIn(anyList()))
                    .thenReturn(Collections.singletonList(inactiveProduct));

            BatchProductPriceResponse response = productService.getBatchProductPrices(inputCodes);

            assertThat(response.isAllProductsOrderable()).isFalse();
            assertThat(response.getProducts()).hasSize(1);
            assertThat(response.getMissingProductCodes()).isEmpty();
        }

        @Test
        @DisplayName("应该按输入顺序返回商品列表")
        void shouldReturnProductsInInputOrder() {
            List<Long> inputCodes = Arrays.asList(10010003L, EXISTING_PRODUCT_CODE, 10010002L);

            Product product3 = new Product();
            product3.setProductCode(10010003L);
            product3.setPrice(new BigDecimal("299.99"));
            product3.setStatus(ProductStatus.ACTIVE);

            Product product2 = new Product();
            product2.setProductCode(10010002L);
            product2.setPrice(new BigDecimal("99.99"));
            product2.setStatus(ProductStatus.ACTIVE);

            when(productBloomFilter.mightContain(anyLong())).thenReturn(true);
            when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList(null, null, null));
            when(productRepository.findProductsByProductCodeIn(anyList()))
                    .thenReturn(Arrays.asList(existingProduct, product2, product3));

            BatchProductPriceResponse response = productService.getBatchProductPrices(inputCodes);

            assertThat(response.getProducts()).hasSize(3);
            assertThat(response.getProducts().get(0).getProductCode()).isEqualTo(10010003L);
            assertThat(response.getProducts().get(1).getProductCode()).isEqualTo(EXISTING_PRODUCT_CODE);
            assertThat(response.getProducts().get(2).getProductCode()).isEqualTo(10010002L);
        }
    }

    // ========================================================================
    // deleteProduct 测试
    // ========================================================================
    @Nested
    @DisplayName("deleteProduct 方法测试")
    class DeleteProductTests {

        @Test
        @DisplayName("应该成功删除存在的商品")
        void shouldDeleteExistingProduct() {
            String cacheKey = CACHE_KEY_PREFIX + EXISTING_PRODUCT_CODE;

            when(productRepository.findProductByProductCode(EXISTING_PRODUCT_CODE))
                    .thenReturn(Optional.of(existingProduct));
            when(productEventMapper.toDeletedEvent(any(Product.class))).thenReturn(null);

            productService.deleteProduct(EXISTING_PRODUCT_CODE);

            // delete 是 RedisTemplate 的方法
            verify(redisTemplate, times(2)).delete(cacheKey);
            verify(productRepository).delete(existingProduct);
            verify(productSyncProducer).publishDeleteProductEvent(any());
        }

        @Test
        @DisplayName("应该抛出异常当商品不存在")
        void shouldThrowExceptionWhenProductNotFound() {
            when(productRepository.findProductByProductCode(NON_EXISTENT_PRODUCT_CODE))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.deleteProduct(NON_EXISTENT_PRODUCT_CODE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Product not found: " + NON_EXISTENT_PRODUCT_CODE);

            verify(productRepository, never()).delete(any());
        }
    }

    // ========================================================================
    // addProduct 测试
    // ========================================================================
    @Nested
    @DisplayName("addProduct 方法测试")
    class AddProductTests {

        @Test
        @DisplayName("应该成功创建商品")
        void shouldCreateProductSuccessfully() {
            ProductCreateRequest request = new ProductCreateRequest();
            request.setProductName("New Product");
            request.setPrice(new BigDecimal("99.99"));
            request.setBrand("NewBrand");
            request.setCategoryCode(CategoryCode.ELECTRONICS);
            request.setDescription("New Description");

            Product savedProduct = new Product();
            savedProduct.setProductCode(1234567890L);
            savedProduct.setProductName("New Product");
            savedProduct.setPrice(new BigDecimal("99.99"));
            savedProduct.setStatus(ProductStatus.ACTIVE);

            ProductDetail detail = new ProductDetail();
            detail.setBrand("NewBrand");
            detail.setCategoryCode(CategoryCode.ELECTRONICS);
            detail.setDescription("New Description");
            savedProduct.setProductDetail(detail);

            when(productRepository.save(any(Product.class))).thenReturn(savedProduct);
            when(productEventMapper.toCreateEvent(any(Product.class))).thenReturn(null);

            ProductResponse response = productService.addProduct(request);

            assertThat(response).isNotNull();
            assertThat(response.getProductName()).isEqualTo("New Product");
            assertThat(response.getPrice()).isEqualTo(new BigDecimal("99.99"));

            verify(productBloomFilter).add(anyLong());
            verify(productSyncProducer).publishCreateProductEvent(any());
        }
    }

    // ========================================================================
    // updateProduct 测试
    // ========================================================================
    @Nested
    @DisplayName("updateProduct 方法测试")
    class UpdateProductTests {

        @Test
        @DisplayName("应该成功更新商品的部分字段")
        void shouldUpdateProductPartially() {
            String cacheKey = CACHE_KEY_PREFIX + EXISTING_PRODUCT_CODE;

            ProductUpdateRequest request = new ProductUpdateRequest();
            request.setPrice(new BigDecimal("299.99"));
            request.setBrand("UpdatedBrand");
            request.setCategoryCode(CategoryCode.BOOKS);
            request.setDescription("Updated Description");

            when(productRepository.findProductByProductCode(EXISTING_PRODUCT_CODE))
                    .thenReturn(Optional.of(existingProduct));
            when(productRepository.save(any(Product.class))).thenReturn(existingProduct);
            when(productEventMapper.toUpdatedEvent(any(Product.class))).thenReturn(null);

            ProductResponse response = productService.updateProduct(EXISTING_PRODUCT_CODE, request);

            assertThat(response).isNotNull();
            assertThat(response.getPrice()).isEqualTo(new BigDecimal("299.99"));
            assertThat(response.getBrand()).isEqualTo("UpdatedBrand");
            assertThat(response.getCategoryCode()).isEqualTo(CategoryCode.BOOKS);

            // delete 是 RedisTemplate 的方法
            verify(redisTemplate, times(2)).delete(cacheKey);
            verify(productSyncProducer).publishUpdateProductEvent(any());
        }

        @Test
        @DisplayName("应该成功更新商品当请求只包含部分字段")
        void shouldUpdateProductWhenRequestHasPartialFields() {
            ProductUpdateRequest request = new ProductUpdateRequest();
            request.setPrice(new BigDecimal("299.99"));
            // brand, categoryCode, description are null

            when(productRepository.findProductByProductCode(EXISTING_PRODUCT_CODE))
                    .thenReturn(Optional.of(existingProduct));
            when(productRepository.save(any(Product.class))).thenReturn(existingProduct);
            when(productEventMapper.toUpdatedEvent(any(Product.class))).thenReturn(null);

            ProductResponse response = productService.updateProduct(EXISTING_PRODUCT_CODE, request);

            assertThat(response).isNotNull();
            assertThat(response.getPrice()).isEqualTo(new BigDecimal("299.99"));
            // Other fields should remain unchanged
            assertThat(response.getBrand()).isEqualTo("TestBrand");
        }

        @Test
        @DisplayName("应该抛出异常当更新的商品不存在")
        void shouldThrowExceptionWhenProductNotFound() {
            ProductUpdateRequest request = new ProductUpdateRequest();
            request.setPrice(new BigDecimal("100.00"));
            request.setBrand("Brand");
            request.setCategoryCode(CategoryCode.ELECTRONICS);

            when(productRepository.findProductByProductCode(NON_EXISTENT_PRODUCT_CODE))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.updateProduct(NON_EXISTENT_PRODUCT_CODE, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Product not found: " + NON_EXISTENT_PRODUCT_CODE);
        }
    }
}