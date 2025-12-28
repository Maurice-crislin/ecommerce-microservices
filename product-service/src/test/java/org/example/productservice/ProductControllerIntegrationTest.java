package org.example.productservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import org.example.productservice.dto.BatchProductPriceRequest;
import org.example.productservice.entity.Product;
import org.example.productservice.enums.ProductStatus;
import org.example.productservice.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;


import java.math.BigDecimal;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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


    /**
     * Test single product price retrieval for an existing product.
     */
    @Test
    void testGetProductPrice_Success() throws Exception {
        mockMvc.perform(get("/products/10010001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCode").value(10010001))
                .andExpect(jsonPath("$.price").value(199.99))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    /**
     * Test single product price retrieval for a non-existing product.
     * Should return 4xx client error.
     */
    @Test
    void testGetProductPrice_NotFound() throws Exception {
        mockMvc.perform(get("/products/99999999"))
                .andExpect(status().is4xxClientError());
    }

    /**
     * Test batch product price retrieval for multiple existing products.
     */
    @Test
    void testGetAllProductPrices_Success() throws Exception {
        BatchProductPriceRequest request = new BatchProductPriceRequest();
        request.setProductCodes(List.of(10010001L, 10010002L, 10010003L));

        mockMvc.perform(post("/products/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].productCode").value(10010001))
                .andExpect(jsonPath("$[1].productCode").value(10010002))
                .andExpect(jsonPath("$[2].productCode").value(10010003));
    }

    /**
     * Test batch product price retrieval with an empty request list.
     * Should return HTTP 400 Bad Request and empty array.
     */
    @Test
    void testGetAllProductPrices_EmptyRequest() throws Exception {
        BatchProductPriceRequest request = new BatchProductPriceRequest();
        request.setProductCodes(List.of());

        mockMvc.perform(post("/products/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * Test batch product price retrieval where some products exist and some do not.
     * Only existing products should be returned.
     */
    @Test
    void testGetAllProductPrices_PartialExist() throws Exception {
        BatchProductPriceRequest request = new BatchProductPriceRequest();
        request.setProductCodes(List.of(10010001L, 99999999L, 10010003L));

        mockMvc.perform(post("/products/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].productCode").value(10010001))
                .andExpect(jsonPath("$[1].productCode").value(10010003));
    }

    /**
     * Test single product retrieval for a product with INACTIVE status.
     */
    @Test
    void testGetProductPrice_InactiveProduct() throws Exception {
        mockMvc.perform(get("/products/10010005"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCode").value(10010005))
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    /**
     * Test batch retrieval including INACTIVE product.
     * Both ACTIVE and INACTIVE products should be returned.
     */
    @Test
    void testGetAllProductPrices_WithInactiveProduct() throws Exception {
        BatchProductPriceRequest request = new BatchProductPriceRequest();
        request.setProductCodes(List.of(10010002L, 10010005L));

        mockMvc.perform(post("/products/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[1].status").value("INACTIVE"));
    }
}

