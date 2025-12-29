package org.example.inventoryservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.inventoryservice.domain.Inventory;
import org.example.inventoryservice.dto.StockRequest;
import org.example.inventoryservice.repository.InventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class InventoryIntegrationTestWithExceptions {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private final Long productCode1 = 1001L;
    private final Long productCode2 = 1002L;
    private final Long invalidProductCode = 9999L;

    @BeforeEach
    void setUp() {
        // Clear table and insert initial data before each test
        inventoryRepository.deleteAll();
        Inventory i1 = new Inventory(productCode1, 50);
        Inventory i2 = new Inventory(productCode2, 100);
        inventoryRepository.save(i1);
        inventoryRepository.save(i2);
    }

    // ---------------- Normal scenarios ----------------

    @Test
    void testAddStock_AdminApi() throws Exception {
        StockRequest request = new StockRequest();
        setStockRequest(request, productCode1, 20);

        mockMvc.perform(post("/admin/inventories/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().string("stock has been successfully added"));

        Inventory inventory = inventoryRepository.findInventoryByProductCode(productCode1).get();
        assertThat(inventory.getAvailableStock()).isEqualTo(70);
    }

    @Test
    void testDeductStock_AdminApi() throws Exception {
        StockRequest request = new StockRequest();
        setStockRequest(request, productCode1, 30);

        mockMvc.perform(post("/admin/inventories/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().string("stock has been successfully deducted"));

        Inventory inventory = inventoryRepository.findInventoryByProductCode(productCode1).get();
        assertThat(inventory.getAvailableStock()).isEqualTo(20);
    }

    @Test
    void testLockUnlockConfirmStock() throws Exception {
        // Lock
        StockRequest lockRequest = new StockRequest();
        setStockRequest(lockRequest, productCode2, 40);

        mockMvc.perform(post("/inventories/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(lockRequest)))
                .andExpect(status().isOk())
                .andExpect(content().string("stock has been successfully locked"));

        Inventory inventory = inventoryRepository.findInventoryByProductCode(productCode2).get();
        assertThat(inventory.getAvailableStock()).isEqualTo(60);
        assertThat(inventory.getLockedStock()).isEqualTo(40);

        // Unlock
        StockRequest unlockRequest = new StockRequest();
        setStockRequest(unlockRequest, productCode2, 20);

        mockMvc.perform(post("/inventories/unlock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unlockRequest)))
                .andExpect(status().isOk())
                .andExpect(content().string("stock has been successfully unlocked"));

        Inventory updated = inventoryRepository.findInventoryByProductCode(productCode2).get();
        assertThat(updated.getAvailableStock()).isEqualTo(80);
        assertThat(updated.getLockedStock()).isEqualTo(20);

        // Confirm sale
        StockRequest confirmRequest = new StockRequest();
        setStockRequest(confirmRequest, productCode2, 20);

        mockMvc.perform(post("/inventories/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmRequest)))
                .andExpect(status().isOk())
                .andExpect(content().string("sale has been successfully confirmed"));

        Inventory finalInv = inventoryRepository.findInventoryByProductCode(productCode2).get();
        assertThat(finalInv.getAvailableStock()).isEqualTo(80);
        assertThat(finalInv.getLockedStock()).isEqualTo(0);
        assertThat(finalInv.getSoldStock()).isEqualTo(20);
    }

    // ---------------- Exception scenarios ----------------

    @Test
    void testDeductStock_Insufficient() throws Exception {
        StockRequest request = new StockRequest();
        setStockRequest(request, productCode1, 100); // more than available

        mockMvc.perform(post("/admin/inventories/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("out of stock"));
    }

    @Test
    void testAddStock_InvalidQuantity() throws Exception {
        StockRequest request = new StockRequest();
        setStockRequest(request, productCode1, 0); // invalid quantity

        mockMvc.perform(post("/admin/inventories/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testLockStock_Insufficient() throws Exception {
        StockRequest request = new StockRequest();
        setStockRequest(request, productCode1, 100); // more than available

        mockMvc.perform(post("/inventories/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("out of stock"));
    }

    @Test
    void testUnlockStock_ExceedsLocked() throws Exception {
        Inventory inventory = inventoryRepository.findInventoryByProductCode(productCode1).get();
        inventory.lock(10);
        inventoryRepository.save(inventory);

        StockRequest request = new StockRequest();
        setStockRequest(request, productCode1, 20); // more than locked

        mockMvc.perform(post("/inventories/unlock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Cannot unlock more than locked stock"));
    }

    @Test
    void testConfirmSale_InsufficientLocked() throws Exception {
        Inventory inventory = inventoryRepository.findInventoryByProductCode(productCode1).get();
        inventory.lock(5);
        inventoryRepository.save(inventory);

        StockRequest request = new StockRequest();
        setStockRequest(request, productCode1, 10); // more than locked

        mockMvc.perform(post("/inventories/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Not enough locked stock to confirm sale"));
    }

    @Test
    void testNonExistingProduct() throws Exception {
        StockRequest request = new StockRequest();
        setStockRequest(request, invalidProductCode, 10);

        // Deduct
        mockMvc.perform(post("/admin/inventories/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Product not found"));

        // Lock
        mockMvc.perform(post("/inventories/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Product not found"));
    }

    // ---------------- Helper method ----------------

    private void setStockRequest(StockRequest request, Long productCode, Integer quantity) throws Exception {
        // Using reflection to set private fields in StockRequest
        java.lang.reflect.Field productCodeField = StockRequest.class.getDeclaredField("productCode");
        productCodeField.setAccessible(true);
        productCodeField.set(request, productCode);

        java.lang.reflect.Field quantityField = StockRequest.class.getDeclaredField("quantity");
        quantityField.setAccessible(true);
        quantityField.set(request, quantity);
    }
}
