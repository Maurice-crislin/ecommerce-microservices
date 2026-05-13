package org.example.productservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.productservice.controller.ProductController;
import org.example.productservice.dto.*;
import org.example.productservice.enums.ProductStatus;
import org.example.productservice.service.ProductService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProductController.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ProductService productService;

    private static final Long PRODUCT_CODE_1 = 10010001L;
    private static final Long NON_EXISTENT_CODE = 99999999L;

    // ========================================================================
    // GET /products/{productCode}
    // ========================================================================
    @Nested
    @DisplayName("GET /products/{productCode}")
    class GetProductPrice {

        @Test
        @DisplayName("Should return 200 with product price when found")
        void shouldReturn200WhenFound() throws Exception {
            ProductPriceResponse response = new ProductPriceResponse(PRODUCT_CODE_1, new BigDecimal("199.99"), ProductStatus.ACTIVE);
            when(productService.getProductPrice(PRODUCT_CODE_1)).thenReturn(response);

            mockMvc.perform(get("/products/{productCode}", PRODUCT_CODE_1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.productCode").value(PRODUCT_CODE_1))
                    .andExpect(jsonPath("$.price").value(199.99))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        @Test
        @DisplayName("Should return 404 when product not found")
        void shouldReturn404WhenNotFound() throws Exception {
            when(productService.getProductPrice(NON_EXISTENT_CODE)).thenThrow(new IllegalArgumentException("Product not found: " + NON_EXISTENT_CODE));

            mockMvc.perform(get("/products/{productCode}", NON_EXISTENT_CODE))
                    .andExpect(status().isNotFound());
        }
    }

    // ========================================================================
    // POST /products/batch
    // ========================================================================
    @Nested
    @DisplayName("POST /products/batch")
    class BatchQuery {

        @Test
        @DisplayName("Should return 200 with batch response")
        void shouldReturn200() throws Exception {
            List<Long> codes = Arrays.asList(PRODUCT_CODE_1, 10010002L);
            BatchProductPriceRequest request = new BatchProductPriceRequest();
            request.setProductCodes(codes);

            List<ProductPriceResponse> products = Arrays.asList(
                    new ProductPriceResponse(PRODUCT_CODE_1, new BigDecimal("199.99"), ProductStatus.ACTIVE),
                    new ProductPriceResponse(10010002L, new BigDecimal("99.99"), ProductStatus.ACTIVE)
            );
            BatchProductPriceResponse serviceResponse = new BatchProductPriceResponse(true, products, Collections.emptyList());

            when(productService.getBatchProductPrices(codes)).thenReturn(serviceResponse);

            mockMvc.perform(post("/products/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.allProductsOrderable").value(true))
                    .andExpect(jsonPath("$.products").isArray())
                    .andExpect(jsonPath("$.products.length()").value(2))
                    .andExpect(jsonPath("$.missingProductCodes").isEmpty());
        }

        @Test
        @DisplayName("Should return 400 when request body has null codes")
        void shouldReturn400WhenCodesNull() throws Exception {
            BatchProductPriceRequest request = new BatchProductPriceRequest();

            mockMvc.perform(post("/products/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 400 when codes list is empty")
        void shouldReturn400WhenCodesEmpty() throws Exception {
            BatchProductPriceRequest request = new BatchProductPriceRequest();
            request.setProductCodes(Collections.emptyList());

            mockMvc.perform(post("/products/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ========================================================================
    // POST /products/add
    // ========================================================================
    @Nested
    @DisplayName("POST /products/add")
    class AddProduct {

        @Test
        @DisplayName("Should return 200 with created product")
        void shouldReturn200() throws Exception {
            ProductCreateRequest request = new ProductCreateRequest();
            request.setProductName("New Product");
            request.setPrice(new BigDecimal("99.99"));

            ProductResponse serviceResponse = new ProductResponse(PRODUCT_CODE_1, "New Product", new BigDecimal("99.99"), ProductStatus.ACTIVE);
            when(productService.addProduct(any(ProductCreateRequest.class))).thenReturn(serviceResponse);

            mockMvc.perform(post("/products/add")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.productCode").value(PRODUCT_CODE_1))
                    .andExpect(jsonPath("$.productName").value("New Product"))
                    .andExpect(jsonPath("$.price").value(99.99))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }
    }

    // ========================================================================
    // POST /products/update/{productCode}
    // ========================================================================
    @Nested
    @DisplayName("POST /products/update/{productCode}")
    class UpdateProduct {

        @Test
        @DisplayName("Should return 200 with updated product")
        void shouldReturn200() throws Exception {
            ProductUpdateRequest request = new ProductUpdateRequest(new BigDecimal("299.99"));

            ProductResponse serviceResponse = new ProductResponse(PRODUCT_CODE_1, "Updated Product", new BigDecimal("299.99"), ProductStatus.ACTIVE);
            when(productService.updateProduct(eq(PRODUCT_CODE_1), any(ProductUpdateRequest.class))).thenReturn(serviceResponse);

            mockMvc.perform(post("/products/update/{productCode}", PRODUCT_CODE_1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.productCode").value(PRODUCT_CODE_1))
                    .andExpect(jsonPath("$.price").value(299.99));
        }

        @Test
        @DisplayName("Should return 404 when product not found")
        void shouldReturn404WhenNotFound() throws Exception {
            ProductUpdateRequest request = new ProductUpdateRequest(new BigDecimal("100.00"));

            when(productService.updateProduct(eq(NON_EXISTENT_CODE), any(ProductUpdateRequest.class)))
                    .thenThrow(new IllegalArgumentException("Product not found: " + NON_EXISTENT_CODE));

            mockMvc.perform(post("/products/update/{productCode}", NON_EXISTENT_CODE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }
    }

    // ========================================================================
    // POST /products/delete/{productCode}
    // ========================================================================
    @Nested
    @DisplayName("POST /products/delete/{productCode}")
    class DeleteProduct {

        @Test
        @DisplayName("Should return 204 when delete succeeds")
        void shouldReturn204() throws Exception {
            mockMvc.perform(post("/products/delete/{productCode}", PRODUCT_CODE_1))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("Should return 404 when product not found (IllegalArgumentException)")
        void shouldReturn404WhenNotFound() throws Exception {
            doThrow(new IllegalArgumentException("Product not found: " + NON_EXISTENT_CODE))
                    .when(productService).deleteProduct(NON_EXISTENT_CODE);

            mockMvc.perform(post("/products/delete/{productCode}", NON_EXISTENT_CODE))
                    .andExpect(status().isNotFound());
        }
    }
}