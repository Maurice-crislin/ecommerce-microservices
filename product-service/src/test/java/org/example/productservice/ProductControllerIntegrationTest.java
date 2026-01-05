package org.example.productservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.productservice.dto.BatchProductPriceRequest;
import org.example.productservice.dto.BatchProductPriceResponse;
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

import java.math.BigDecimal;
import java.util.List;

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

    // =================== 单条查询 ===================
    @Test
    void testGetProductPrice_Success() throws Exception {
        mockMvc.perform(get("/products/10010001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCode").value(10010001))
                .andExpect(jsonPath("$.price").value(199.99))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void testGetProductPrice_NotFound() throws Exception {
        mockMvc.perform(get("/products/99999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testGetProductPrice_InactiveProduct() throws Exception {
        mockMvc.perform(get("/products/10010005"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCode").value(10010005))
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    // =================== 批量查询 ===================
    @Test
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
    void testGetAllProductPrices_WithInactiveProduct() throws Exception {
        BatchProductPriceRequest request = new BatchProductPriceRequest();
        request.setProductCodes(List.of(10010002L, 10010005L));

        mockMvc.perform(post("/products/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allProductsOrderable").value(false)) // 有 INACTIVE
                .andExpect(jsonPath("$.products", hasSize(2)))
                .andExpect(jsonPath("$.missingProductCodes", hasSize(0)))
                .andExpect(jsonPath("$.products[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.products[1].status").value("INACTIVE"));
    }

    @Test
    void testGetAllProductPrices_EmptyRequest() throws Exception {
        BatchProductPriceRequest request = new BatchProductPriceRequest();
        request.setProductCodes(List.of());

        mockMvc.perform(post("/products/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
