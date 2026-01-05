package org.example.inventoryservice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.dto.StockRequest;
import org.common.product.dto.InventoryBatchCheckResult;
import org.example.inventoryservice.domain.Inventory;
import org.example.inventoryservice.dto.*;
import org.example.inventoryservice.repository.InventoryOperationRepository;
import org.example.inventoryservice.repository.InventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class InventoryApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryOperationRepository inventoryOperationRepository;
    private List<StockRequest> stockRequestsAllAvailable;
    private List<StockRequest> stockRequestsPartial;

    @BeforeEach
    void init() {
        inventoryOperationRepository.deleteAll();
        inventoryRepository.deleteAll();

        List<Inventory> inventories = List.of(
                new Inventory(1001L, 50),
                new Inventory(1002L, 100),
                new Inventory(1003L, 200),
                new Inventory(1004L, 10),
                new Inventory(1005L, 5),
                new Inventory(1006L, 10),
                new Inventory(1007L, 500),
                new Inventory(1008L, 250),
                new Inventory(1009L, 1),
                new Inventory(1010L, 0),
                new Inventory(1011L, 1000),
                new Inventory(1012L, 30)
        );

        inventoryRepository.saveAll(inventories);
    }




    @BeforeEach
    void setup() {
        // 12个产品都在datasql里，你可以挑几种情况测试
        stockRequestsAllAvailable = List.of(
                new StockRequest(1001L, 1),
                new StockRequest(1002L, 2),
                new StockRequest(1003L, 5)
        );

        stockRequestsPartial = List.of(
                new StockRequest(1004L, 1), // 0库存
                new StockRequest(1005L, 10), // 只有5库存
                new StockRequest(1006L, 5)  // 有库存
        );
    }

    // ----------------- Admin 接口 -----------------

    @Test
    void testAdminDeductStock_success() throws Exception {
        StockRequest request = new StockRequest(1001L, 2);

        MvcResult result = mockMvc.perform(post("/admin/inventories/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        SimpleResponse<Object> response = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                new TypeReference<SimpleResponse<Object>>() {}
        );

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMessage()).contains("successfully deducted");
    }

    @Test
    void testAdminAddStock_success() throws Exception {
        StockRequest request = new StockRequest(1002L, 5);

        MvcResult result = mockMvc.perform(post("/admin/inventories/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        SimpleResponse<Object> response = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                new TypeReference<SimpleResponse<Object>>() {}
        );

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMessage()).contains("successfully added");
    }

    // ----------------- 普通接口 -----------------

    @Test
    void testBatchLockStock_success() throws Exception {
        InventoryBatchRequest event = new InventoryBatchRequest(1L, stockRequestsAllAvailable);

        MvcResult result = mockMvc.perform(post("/inventories/batch/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isOk())
                .andReturn();

        SimpleResponse<Object> response = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                new TypeReference<SimpleResponse<Object>>() {}
        );

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMessage()).contains("Stock locked successfully");
    }

    @Test
    void testBatchCheckStock_allAvailable() throws Exception {
        InventoryBatchRequest event = new InventoryBatchRequest(1L, stockRequestsAllAvailable);

        MvcResult result = mockMvc.perform(post("/inventories/batch/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isOk())
                .andReturn();

        SimpleResponse<InventoryBatchCheckResult> response =
                objectMapper.readValue(
                        result.getResponse().getContentAsString(),
                        new TypeReference<SimpleResponse<InventoryBatchCheckResult>>() {}
                );

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().isAllValid()).isTrue();
        assertThat(response.getData().getFailedProductCodes()).isEmpty();
    }

    @Test
    void testBatchCheckStock_partialAvailable() throws Exception {
        InventoryBatchRequest event = new InventoryBatchRequest(1L, stockRequestsPartial);

        MvcResult result = mockMvc.perform(post("/inventories/batch/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isOk())
                .andReturn();

        SimpleResponse<InventoryBatchCheckResult> response =
                objectMapper.readValue(
                        result.getResponse().getContentAsString(),
                        new TypeReference<SimpleResponse<InventoryBatchCheckResult>>() {}
                );

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().isAllValid()).isFalse();
        assertThat(response.getData().getFailedProductCodes())
                .containsExactlyInAnyOrder(1005L);
    }
}
