package org.example.productservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.productservice.dto.*;
import org.example.productservice.entity.Product;
import org.example.productservice.enums.ProductStatus;
import org.example.productservice.repository.ProductRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @BeforeEach
    void init() {
        productRepository.deleteAll();
        productRepository.saveAll(List.of(
                new Product(null, 10010001L, "Mechanical Keyboard", new BigDecimal("199.99"), ProductStatus.ACTIVE),
                new Product(null, 10010002L, "Wireless Mouse", new BigDecimal("99.99"), ProductStatus.ACTIVE),
                new Product(null, 10010003L, "Gaming Headset", new BigDecimal("149.99"), ProductStatus.ACTIVE),
                new Product(null, 10010005L, "27-inch Monitor", new BigDecimal("299.99"), ProductStatus.INACTIVE)
        ));
    }

    // ========================================================================
    // 单条查询 - GET /products/{productCode}
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
    // 批量查询 - POST /products/batch
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
                    .andExpect(jsonPath("$.missingProductCodes", hasSize(0)))
                    .andExpect(jsonPath("$.products[0].productCode").value(10010001))
                    .andExpect(jsonPath("$.products[1].productCode").value(10010002))
                    .andExpect(jsonPath("$.products[2].productCode").value(10010003));
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
                    .andExpect(jsonPath("$.missingProductCodes[0]").value(99999999))
                    .andExpect(jsonPath("$.products[0].productCode").value(10010001))
                    .andExpect(jsonPath("$.products[1].productCode").value(10010003));
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
                    .andExpect(jsonPath("$.products", hasSize(2)))
                    .andExpect(jsonPath("$.missingProductCodes", hasSize(0)))
                    .andExpect(jsonPath("$.products[0].status").value("ACTIVE"))
                    .andExpect(jsonPath("$.products[1].status").value("INACTIVE"));
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
        @DisplayName("Should return 400 for null codes (validation error)")
        void testGetAllProductPrices_NullCodes() throws Exception {
            BatchProductPriceRequest request = new BatchProductPriceRequest();
            request.setProductCodes(null);

            mockMvc.perform(post("/products/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should preserve request order in response")
        void testBatchPreservesOrder() throws Exception {
            BatchProductPriceRequest request = new BatchProductPriceRequest();
            // Request in reverse order
            request.setProductCodes(List.of(10010003L, 10010001L, 10010002L));

            mockMvc.perform(post("/products/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.products[0].productCode").value(10010003))
                    .andExpect(jsonPath("$.products[1].productCode").value(10010001))
                    .andExpect(jsonPath("$.products[2].productCode").value(10010002));
        }

        @Test
        @DisplayName("Should handle mixed ACTIVE/INACTIVE/missing products")
        void testBatchMixedScenarios() throws Exception {
            BatchProductPriceRequest request = new BatchProductPriceRequest();
            request.setProductCodes(List.of(10010001L, 99999999L, 10010005L));

            mockMvc.perform(post("/products/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.allProductsOrderable").value(false))
                    .andExpect(jsonPath("$.products", hasSize(2)))
                    .andExpect(jsonPath("$.missingProductCodes", hasSize(1)))
                    .andExpect(jsonPath("$.products[0].status").value("ACTIVE"))
                    .andExpect(jsonPath("$.products[1].status").value("INACTIVE"));
        }
    }

    // ========================================================================
    // 新增商品 - POST /products/add
    // ========================================================================
    @Nested
    @DisplayName("POST /products/add - Create product")
    class AddProduct {

        @Test
        @DisplayName("Should create a new product and return its details")
        void testAddProduct_Success() throws Exception {
            ProductCreateRequest request = new ProductCreateRequest();
            request.setProductName("New Product");
            request.setPrice(new BigDecimal("99.99"));

            mockMvc.perform(post("/products/add")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.productName").value("New Product"))
                    .andExpect(jsonPath("$.price").value(99.99))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        @Test
        @DisplayName("Should persist product to database")
        void testAddProduct_Persists() throws Exception {
            ProductCreateRequest request = new ProductCreateRequest();
            request.setProductName("Persist Test Product");
            request.setPrice(new BigDecimal("49.99"));

            String content = mockMvc.perform(post("/products/add")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            ProductResponse response = objectMapper.readValue(content, ProductResponse.class);

            // Verify it's in the DB
            Optional<Product> saved = productRepository.findProductByProductCode(response.getProductCode());
            assertThat(saved).isPresent();
            assertThat(saved.get().getProductName()).isEqualTo("Persist Test Product");
            assertThat(saved.get().getPrice()).isEqualByComparingTo(new BigDecimal("49.99"));
            assertThat(saved.get().getStatus()).isEqualTo(ProductStatus.ACTIVE);
        }
    }

    // ========================================================================
    // 更新商品 - POST /products/update/{productCode}
    // ========================================================================
    @Nested
    @DisplayName("POST /products/update/{productCode} - Update product")
    class UpdateProduct {

        @Test
        @DisplayName("Should update product price and return updated details")
        void testUpdateProduct_Success() throws Exception {
            ProductUpdateRequest request = new ProductUpdateRequest(new BigDecimal("299.99"));

            mockMvc.perform(post("/products/update/10010001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.productCode").value(10010001))
                    .andExpect(jsonPath("$.price").value(299.99));
        }

        @Test
        @DisplayName("Should persist price update to database")
        void testUpdateProduct_Persists() throws Exception {
            ProductUpdateRequest request = new ProductUpdateRequest(new BigDecimal("399.99"));

            mockMvc.perform(post("/products/update/10010001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());

            Optional<Product> updated = productRepository.findProductByProductCode(10010001L);
            assertThat(updated).isPresent();
            assertThat(updated.get().getPrice()).isEqualByComparingTo(new BigDecimal("399.99"));
        }

        @Test
        @DisplayName("Should return 500 when updating non-existent product")
        void testUpdateProduct_NotFound() throws Exception {
            ProductUpdateRequest request = new ProductUpdateRequest(new BigDecimal("100.00"));

            mockMvc.perform(post("/products/update/99999999")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }
    }

    // ========================================================================
    // 删除商品 - POST /products/delete/{productCode}
    // ========================================================================
    @Nested
    @DisplayName("POST /products/delete/{productCode} - Delete product")
    class DeleteProduct {

        @Test
        @DisplayName("Should delete product and return 204")
        void testDeleteProduct_Success() throws Exception {
            mockMvc.perform(post("/products/delete/10010001"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("Should remove product from database")
        void testDeleteProduct_RemovesFromDb() throws Exception {
            mockMvc.perform(post("/products/delete/10010002"))
                    .andExpect(status().isNoContent());

            Optional<Product> deleted = productRepository.findProductByProductCode(10010002L);
            assertThat(deleted).isNotPresent();
        }

        @Test
        @DisplayName("Should return 404 when deleting non-existent product")
        void testDeleteProduct_NotFound() throws Exception {
            mockMvc.perform(post("/products/delete/99999999"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should only delete the specified product")
        void testDeleteProduct_OnlyDeletesTarget() throws Exception {
            mockMvc.perform(post("/products/delete/10010001"))
                    .andExpect(status().isNoContent());

            // Other products should remain
            assertThat(productRepository.findProductByProductCode(10010002L)).isPresent();
            assertThat(productRepository.findProductByProductCode(10010003L)).isPresent();
            assertThat(productRepository.findProductByProductCode(10010005L)).isPresent();
        }
    }

    // ========================================================================
    // 综合流程 - 多个操作组合
    // ========================================================================
    @Nested
    @DisplayName("Combined workflows")
    class CombinedWorkflows {

        @Test
        @DisplayName("Add a product, then query it, then update it, then delete it")
        void testFullLifecycle() throws Exception {
            // 1. Add
            ProductCreateRequest createRequest = new ProductCreateRequest();
            createRequest.setProductName("Lifecycle Product");
            createRequest.setPrice(new BigDecimal("25.00"));

            String addResponse = mockMvc.perform(post("/products/add")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createRequest)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            ProductResponse created = objectMapper.readValue(addResponse, ProductResponse.class);
            Long newCode = created.getProductCode();

            // 2. Query it
            mockMvc.perform(get("/products/{productCode}", newCode))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.productCode").value(newCode))
                    .andExpect(jsonPath("$.price").value(25.00))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));

            // 3. Update it
            ProductUpdateRequest updateRequest = new ProductUpdateRequest(new BigDecimal("75.00"));
            mockMvc.perform(post("/products/update/{productCode}", newCode)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.price").value(75.00));

            // 4. Delete it
            mockMvc.perform(post("/products/delete/{productCode}", newCode))
                    .andExpect(status().isNoContent());

            // 5. Confirm deletion
            mockMvc.perform(get("/products/{productCode}", newCode))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Add product and verify it appears in batch query")
        void testBatchIncludesNewlyAddedProduct() throws Exception {
            ProductCreateRequest createRequest = new ProductCreateRequest();
            createRequest.setProductName("Batch Test Product");
            createRequest.setPrice(new BigDecimal("30.00"));

            String addResponse = mockMvc.perform(post("/products/add")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createRequest)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            ProductResponse created = objectMapper.readValue(addResponse, ProductResponse.class);

            // Batch query should find it
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
    }
}