package org.example.inventoryservice;

import jakarta.persistence.OptimisticLockException;
import org.example.inventoryservice.controller.InventoryController;
import org.example.inventoryservice.service.InventoryService;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;


import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@WebMvcTest(InventoryController.class)
public class InventoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InventoryService inventoryService;

    @Test
    void deductStock_success() throws Exception {
        mockMvc.perform(post("/inventories/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {
                      "productCode": 1001,
                      "quantity": 2
                    }
                """))
                .andExpect(status().isOk());
    }

    @Test
    void deductStock_badRequest() throws Exception {
        doThrow(new IllegalArgumentException("out of stock"))
                .when(inventoryService).deductStockDirectly(1001L, 10);

        mockMvc.perform(post("/inventories/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {
                      "productCode": 1001,
                      "quantity": 10
                    }
                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deductStock_conflict() throws Exception {
        doThrow(new OptimisticLockException())
                .when(inventoryService).deductStockDirectly(1001L, 1);

        mockMvc.perform(post("/inventories/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {
                      "productCode": 1001,
                      "quantity": 1
                    }
                """))
                .andExpect(status().isConflict());
    }
}

