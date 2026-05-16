package org.example.productservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.common.product.enums.CategoryCode;
import org.common.product.enums.ProductStatus;
import org.example.productservice.dto.*;
import org.example.productservice.entity.Product;
import org.example.productservice.entity.ProductDetail;
import org.example.productservice.repository.ProductRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class ProductControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private Product createProduct(Long code, String name, BigDecimal price, ProductStatus status,
                                   String brand, CategoryCode categoryCode, String description) {
        Product product = new Product();
        product.setProductCode(code);
        product.setProductName(name);
        product.setPrice(price);
        product.setStatus(status);

        ProductDetail detail = new ProductDetail();
        detail.setBrand(brand);
        detail.setCategoryCode(categoryCode);
        detail.setDescription(description);
        product.setProductDetail(detail);

        return product;
    }

    @BeforeEach
    void init() {
        // 1. Clean MySQL
        productRepository.deleteAll();

        // 2. Clean Redis cache to prevent stale data cross-test contamination
        try {
            redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        } catch (Exception ignored) {
            // Redis may be unavailable; test data will be loaded from DB on cache miss
        }

        productRepository.saveAll(List.of(
                createProduct(10010001L, "Mechanical Keyboard", new BigDecimal("199.99"), ProductStatus.ACTIVE,
                        "BrandA", CategoryCode.ELECTRONICS, "Mechanical Keyboard with RGB lighting"),
                createProduct(10010002L, "Wireless Mouse", new BigDecimal("99.99"), ProductStatus.ACTIVE,
                        "BrandA", CategoryCode.ELECTRONICS, "Wireless Mouse with ergonomic design"),
                createProduct(10010003L, "Gaming Headset", new BigDecimal("149.99"), ProductStatus.ACTIVE,
                        "BrandB", CategoryCode.ELECTRONICS, "Gaming Headset with surround sound"),
                createProduct(10010005L, "27-inch Monitor", new BigDecimal("299.99"), ProductStatus.INACTIVE,
                        "BrandB", CategoryCode.ELECTRONICS, "27-inch Monitor 4K resolution")
        ));
    }

    // ========================================================================
    @Nested
    @DisplayName("GET /products/{productCode} - Single product query")
    class GetProductPrice {
        @Test
        @DisplayName("Should return 200 with product details for existing ACTIVE product")
        void testGetProductPrice_Success() throws Exception {
            mockMvc.perform(get("/products/10010001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.productCode").value(10010001))
                    .andExpect(jsonPath("$.price").value(199.99))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }
        @Test
        @DisplayName("Should return 404 for non-existent product")
        void testGetProductPrice_NotFound() throws Exception {
            mockMvc.perform(get("/products/99999999"))
                    .andExpect(status().isNotFound());
        }
        @Test
        @DisplayName("Should return 200 with INACTIVE status for inactive product")
        void testGetProductPrice_InactiveProduct() throws Exception {
            mockMvc.perform(get("/products/10010005"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.productCode").value(10010005))
                    .andExpect(jsonPath("$.status").value("INACTIVE"));
        }
        @Test
        @DisplayName("Should return 404 for a product code that never existed")
        void testGetProductPrice_NonExistent() throws Exception {
            mockMvc.perform(get("/products/1"))
                    .andExpect(status().isNotFound());
        }
    }

    // ========================================================================
    @Nested
    @DisplayName("POST /products/batch - Batch product query")
    class BatchQuery {
        @Test
        @DisplayName("Should return all products orderable when all exist and are ACTIVE")
        void testGetAllProductPrices_AllExist() throws Exception {
            BatchProductPriceRequest request = new BatchProductPriceRequest();
            request.setProductCodes(List.of(10010001L, 10010002L, 10010003L));
            mockMvc.perform(post("/products/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.allProductsOrderable").value(true))
                    .andExpect(jsonPath("$.products", hasSize(3)))
                    .andExpect(jsonPath("$.missingProductCodes", hasSize(0)));
        }
        @Test
        @DisplayName("Should detect missing products and set allProductsOrderable=false")
        void testGetAllProductPrices_PartialExist() throws Exception {
            BatchProductPriceRequest request = new BatchProductPriceRequest();
            request.setProductCodes(List.of(10010001L, 99999999L, 10010003L));
            mockMvc.perform(post("/products/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.allProductsOrderable").value(false))
                    .andExpect(jsonPath("$.products", hasSize(2)))
                    .andExpect(jsonPath("$.missingProductCodes", hasSize(1)))
                    .andExpect(jsonPath("$.missingProductCodes[0]").value(99999999));
        }
        @Test
        @DisplayName("Should set allProductsOrderable=false when any product is INACTIVE")
        void testGetAllProductPrices_WithInactiveProduct() throws Exception {
            BatchProductPriceRequest request = new BatchProductPriceRequest();
            request.setProductCodes(List.of(10010002L, 10010005L));
            mockMvc.perform(post("/products/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.allProductsOrderable").value(false))
                    .andExpect(jsonPath("$.products", hasSize(2)));
        }
        @Test
        @DisplayName("Should return 400 for empty codes list")
        void testGetAllProductPrices_EmptyRequest() throws Exception {
            BatchProductPriceRequest request = new BatchProductPriceRequest();
            request.setProductCodes(List.of());
            mockMvc.perform(post("/products/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
        @Test
        @DisplayName("Should preserve request order in response")
        void testBatchPreservesOrder() throws Exception {
            BatchProductPriceRequest request = new BatchProductPriceRequest();
            request.setProductCodes(List.of(10010003L, 10010001L, 10010002L));
            mockMvc.perform(post("/products/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.products[0].productCode").value(10010003));
        }
        @Test
        @DisplayName("Should return 400 when codes is null")
        void testGetAllProductPrices_NullRequest() throws Exception {
            BatchProductPriceRequest request = new BatchProductPriceRequest();
            // productCodes is null -> @NotNull validation
            mockMvc.perform(post("/products/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
        @Test
        @DisplayName("Should return allProductsOrderable=false when all products are missing")
        void testGetAllProductPrices_AllMissing() throws Exception {
            BatchProductPriceRequest request = new BatchProductPriceRequest();
            request.setProductCodes(List.of(88888888L, 99999999L));
            mockMvc.perform(post("/products/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.allProductsOrderable").value(false))
                    .andExpect(jsonPath("$.products", hasSize(0)))
                    .andExpect(jsonPath("$.missingProductCodes", hasSize(2)));
        }
    }

    // ========================================================================
    @Nested
    @DisplayName("POST /products - Create product")
    class AddProduct {
        @Test
        @DisplayName("Should create a new product and return its details")
        void testAddProduct_Success() throws Exception {
            ProductCreateRequest request = new ProductCreateRequest();
            request.setProductName("New Product");
            request.setPrice(new BigDecimal("99.99"));
            request.setBrand("TestBrand");
            request.setCategoryCode(CategoryCode.ELECTRONICS);
            request.setDescription("A new test product");
            mockMvc.perform(post("/products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.productName").value("New Product"))
                    .andExpect(jsonPath("$.price").value(99.99))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.brand").value("TestBrand"))
                    .andExpect(jsonPath("$.categoryCode").value("ELECTRONICS"))
                    .andExpect(jsonPath("$.description").value("A new test product"));
        }
        @Test
        @DisplayName("Should persist product to database")
        void testAddProduct_Persists() throws Exception {
            ProductCreateRequest request = new ProductCreateRequest();
            request.setProductName("Persist Test Product");
            request.setPrice(new BigDecimal("49.99"));
            request.setBrand("PersistBrand");
            request.setCategoryCode(CategoryCode.ELECTRONICS);
            request.setDescription("Persist test description");
            String content = mockMvc.perform(post("/products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            ProductResponse response = objectMapper.readValue(content, ProductResponse.class);
            Optional<Product> saved = productRepository.findProductByProductCode(response.getProductCode());
            assertThat(saved).isPresent();
            assertThat(saved.get().getProductName()).isEqualTo("Persist Test Product");
            assertThat(saved.get().getPrice()).isEqualByComparingTo(new BigDecimal("49.99"));
            assertThat(saved.get().getStatus()).isEqualTo(ProductStatus.ACTIVE);
        }
        @Test
        @DisplayName("Should return 500 when required fields are missing (GlobalExceptionHandler catch-all)")
        void testAddProduct_ValidationFailure() throws Exception {
            ProductCreateRequest request = new ProductCreateRequest();
            // All required fields are null/blank
            request.setProductName("");
            request.setPrice(null);
            request.setBrand("");
            request.setCategoryCode(null);
            mockMvc.perform(post("/products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isInternalServerError());
        }
        @Test
        @DisplayName("Should return 201 (200) when product is created with description omitted")
        void testAddProduct_WithoutDescription() throws Exception {
            ProductCreateRequest request = new ProductCreateRequest();
            request.setProductName("Minimal Product");
            request.setPrice(new BigDecimal("19.99"));
            request.setBrand("MinimalBrand");
            request.setCategoryCode(CategoryCode.BOOKS);
            // description is null - optional field
            mockMvc.perform(post("/products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.productName").value("Minimal Product"))
                    .andExpect(jsonPath("$.price").value(19.99))
                    .andExpect(jsonPath("$.brand").value("MinimalBrand"))
                    .andExpect(jsonPath("$.categoryCode").value("BOOKS"));
        }
    }

    // ========================================================================
    @Nested
    @DisplayName("PUT /products/{productCode} - Update product")
    class UpdateProduct {
        @Test
        @DisplayName("Should update product price and return updated details")
        void testUpdateProduct_Success() throws Exception {
            ProductUpdateRequest request = new ProductUpdateRequest();
            request.setPrice(new BigDecimal("299.99"));
            request.setBrand("UpdatedBrand");
            request.setCategoryCode(CategoryCode.ELECTRONICS);
            mockMvc.perform(put("/products/10010001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.productCode").value(10010001))
                    .andExpect(jsonPath("$.price").value(299.99));
        }
        @Test
        @DisplayName("Should persist price update to database")
        void testUpdateProduct_Persists() throws Exception {
            ProductUpdateRequest request = new ProductUpdateRequest();
            request.setPrice(new BigDecimal("399.99"));
            request.setBrand("PersistBrand");
            request.setCategoryCode(CategoryCode.ELECTRONICS);
            mockMvc.perform(put("/products/10010001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
            Optional<Product> updated = productRepository.findProductByProductCode(10010001L);
            assertThat(updated).isPresent();
            assertThat(updated.get().getPrice()).isEqualByComparingTo(new BigDecimal("399.99"));
        }
        @Test
        @DisplayName("Should return 404 when updating non-existent product")
        void testUpdateProduct_NotFound() throws Exception {
            ProductUpdateRequest request = new ProductUpdateRequest();
            request.setPrice(new BigDecimal("100.00"));
            request.setBrand("Brand");
            request.setCategoryCode(CategoryCode.ELECTRONICS);
            mockMvc.perform(put("/products/99999999")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }
        @Test
        @DisplayName("Should allow partial update with only price")
        void testUpdateProduct_PartialUpdate() throws Exception {
            ProductUpdateRequest request = new ProductUpdateRequest();
            request.setPrice(new BigDecimal("250.00"));
            // brand, categoryCode, description are null - partial update
            mockMvc.perform(put("/products/10010001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.productCode").value(10010001))
                    .andExpect(jsonPath("$.price").value(250.00));
        }
        @Test
        @DisplayName("Should allow empty body (no fields to update)")
        void testUpdateProduct_EmptyBody() throws Exception {
            ProductUpdateRequest request = new ProductUpdateRequest();
            // All fields null
            mockMvc.perform(put("/products/10010001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.productCode").value(10010001))
                    .andExpect(jsonPath("$.price").value(199.99)); // unchanged
        }
    }

    // ========================================================================
    @Nested
    @DisplayName("DELETE /products/{productCode} - Delete product")
    class DeleteProduct {
        @Test
        @DisplayName("Should delete product and return 204")
        void testDeleteProduct_Success() throws Exception {
            mockMvc.perform(delete("/products/10010001"))
                    .andExpect(status().isNoContent());
        }
        @Test
        @DisplayName("Should remove product from database")
        void testDeleteProduct_RemovesFromDb() throws Exception {
            mockMvc.perform(delete("/products/10010002"))
                    .andExpect(status().isNoContent());
            Optional<Product> deleted = productRepository.findProductByProductCode(10010002L);
            assertThat(deleted).isNotPresent();
        }
        @Test
        @DisplayName("Should return 404 when deleting non-existent product")
        void testDeleteProduct_NotFound() throws Exception {
            mockMvc.perform(delete("/products/99999999"))
                    .andExpect(status().isNotFound());
        }
        @Test
        @DisplayName("Should only delete the specified product")
        void testDeleteProduct_OnlyDeletesTarget() throws Exception {
            mockMvc.perform(delete("/products/10010001"))
                    .andExpect(status().isNoContent());
            assertThat(productRepository.findProductByProductCode(10010002L)).isPresent();
            assertThat(productRepository.findProductByProductCode(10010003L)).isPresent();
        }
    }

    // ========================================================================
    @Nested
    @DisplayName("Combined workflows")
    class CombinedWorkflows {
        @Test
        @DisplayName("Add a product, then query it, then update it, then delete it")
        void testFullLifecycle() throws Exception {
            ProductCreateRequest createRequest = new ProductCreateRequest();
            createRequest.setProductName("Lifecycle Product");
            createRequest.setPrice(new BigDecimal("25.00"));
            createRequest.setBrand("LifecycleBrand");
            createRequest.setCategoryCode(CategoryCode.ELECTRONICS);
            String addResponse = mockMvc.perform(post("/products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createRequest)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            ProductResponse created = objectMapper.readValue(addResponse, ProductResponse.class);
            Long newCode = created.getProductCode();

            mockMvc.perform(get("/products/{productCode}", newCode))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.productCode").value(newCode));

            ProductUpdateRequest updateRequest = new ProductUpdateRequest();
            updateRequest.setPrice(new BigDecimal("75.00"));
            updateRequest.setBrand("UpdateBrand");
            updateRequest.setCategoryCode(CategoryCode.ELECTRONICS);
            mockMvc.perform(put("/products/{productCode}", newCode)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.price").value(75.00));

            mockMvc.perform(delete("/products/{productCode}", newCode))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/products/{productCode}", newCode))
                    .andExpect(status().isNotFound());
        }
        @Test
        @DisplayName("Add product and verify it appears in batch query")
        void testBatchIncludesNewlyAddedProduct() throws Exception {
            ProductCreateRequest createRequest = new ProductCreateRequest();
            createRequest.setProductName("Batch Test Product");
            createRequest.setPrice(new BigDecimal("30.00"));
            createRequest.setBrand("BatchBrand");
            createRequest.setCategoryCode(CategoryCode.ELECTRONICS);
            String addResponse = mockMvc.perform(post("/products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createRequest)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            ProductResponse created = objectMapper.readValue(addResponse, ProductResponse.class);

            BatchProductPriceRequest batchRequest = new BatchProductPriceRequest();
            batchRequest.setProductCodes(List.of(created.getProductCode(), 10010001L));
            mockMvc.perform(post("/products/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(batchRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.products.length()").value(2))
                    .andExpect(jsonPath("$.missingProductCodes").isEmpty())
                    .andExpect(jsonPath("$.allProductsOrderable").value(true));
        }
        @Test
        @DisplayName("Delete product and verify it disappears from batch query")
        void testDeletedProductNotInBatch() throws Exception {
            mockMvc.perform(delete("/products/10010001"))
                    .andExpect(status().isNoContent());

            BatchProductPriceRequest batchRequest = new BatchProductPriceRequest();
            batchRequest.setProductCodes(List.of(10010001L, 10010002L));
            mockMvc.perform(post("/products/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(batchRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.allProductsOrderable").value(false))
                    .andExpect(jsonPath("$.products.length()").value(1))
                    .andExpect(jsonPath("$.products[0].productCode").value(10010002))
                    .andExpect(jsonPath("$.missingProductCodes[0]").value(10010001));
        }
    }
}