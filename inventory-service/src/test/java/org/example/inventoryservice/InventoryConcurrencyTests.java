
package org.example.inventoryservice;

import java.util.ArrayList;
import java.util.concurrent.*;

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
public class InventoryConcurrencyTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryOperationRepository inventoryOperationRepository;

    private final Long productCodeConcurrent = 1001L; // 并发测试用的产品
    private final Long productCodeIdempotent = 1002L; // 幂等测试用的产品

    @BeforeEach
    void initInventory() {
        // 先删除操作记录
        inventoryOperationRepository.deleteAll();
        inventoryOperationRepository.flush();  // 强制刷新

        // 再删除库存记录
        inventoryRepository.deleteAll();
        inventoryRepository.flush();  // 强制刷新

        inventoryRepository.saveAll(List.of(
                new Inventory(productCodeConcurrent, 50),
                new Inventory(productCodeIdempotent, 20)
        ));

    }

    // ------------------ 并发锁定库存 ------------------
    @Test
    void testConcurrentStockLock() throws Exception {
        int threadCount = 5;
        int quantityPerThread = 20; // 每个线程尝试锁定20件
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        List<Future<SimpleResponse<Object>>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final long orderId = 1000L + i;
            futures.add(executor.submit(() -> {
                try {
                    StockRequest request = new StockRequest(productCodeConcurrent, quantityPerThread);
                    InventoryBatchRequest event = new InventoryBatchRequest(orderId, List.of(request));
                    MvcResult result = mockMvc.perform(post("/inventories/batch/lock")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(event)))
                            .andReturn();

                    return objectMapper.readValue(result.getResponse().getContentAsString(),
                            new TypeReference<SimpleResponse<Object>>() {});
                } finally {
                    latch.countDown();
                }
            }));
        }

        latch.await();
        executor.shutdown();

        int successCount = 0;
        int failCount = 0;
        for (Future<SimpleResponse<Object>> future : futures) {
            SimpleResponse<Object> response = future.get();
            if (response.isSuccess()) successCount++;
            else failCount++;
        }

        System.out.println("Concurrent lock - Success: " + successCount + ", Fail: " + failCount);

        // 校验库存总量没有超卖
        Inventory inventory = inventoryRepository.findInventoryByProductCode(productCodeConcurrent).orElseThrow();
        assertThat(inventory.getAvailableStock() + inventory.getLockedStock()).isEqualTo(50);
    }

    // ------------------ 幂等锁定测试 ------------------
    @Test
    void testIdempotentStockLock() throws Exception {
        Long orderId = 999L;
        StockRequest request = new StockRequest(productCodeIdempotent, 10);
        InventoryBatchRequest event = new InventoryBatchRequest(orderId, List.of(request));

        // 第一次请求
        MvcResult result1 = mockMvc.perform(post("/inventories/batch/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andReturn();
        SimpleResponse<Object> response1 = objectMapper.readValue(
                result1.getResponse().getContentAsString(),
                new TypeReference<SimpleResponse<Object>>() {}
        );
        assertThat(response1.isSuccess()).isTrue();

        Inventory inventory1 = inventoryRepository.findInventoryByProductCode(productCodeIdempotent).orElseThrow();
        assertThat(inventory1.getLockedStock()).isEqualTo(10);

        // 重复请求
        MvcResult result2 = mockMvc.perform(post("/inventories/batch/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andReturn();
        SimpleResponse<Object> response2 = objectMapper.readValue(
                result2.getResponse().getContentAsString(),
                new TypeReference<SimpleResponse<Object>>() {}
        );

        assertThat(response2.isSuccess()).isTrue(); // 成功，但库存未重复扣减

        Inventory inventory2 = inventoryRepository.findInventoryByProductCode(productCodeIdempotent).orElseThrow();
        assertThat(inventory2.getLockedStock()).isEqualTo(10); // 只有第一次锁定生效

        // 重复请求
        MvcResult result3 = mockMvc.perform(post("/inventories/batch/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andReturn();
        SimpleResponse<Object> response3 = objectMapper.readValue(
                result3.getResponse().getContentAsString(),
                new TypeReference<SimpleResponse<Object>>() {}
        );

        assertThat(response3.isSuccess()).isTrue(); // 成功，但库存未重复扣减

        Inventory inventory3 = inventoryRepository.findInventoryByProductCode(productCodeIdempotent).orElseThrow();
        assertThat(inventory3.getLockedStock()).isEqualTo(10); // 只有第一次锁定生效
    }


    // ------------------ 批量库存部分可用测试 ------------------
    @Test
    void testBatchCheckStock_partialAvailable() throws Exception {
        List<StockRequest> stockRequestsPartial = List.of(
                new StockRequest(productCodeConcurrent, 30), // 有库存
                new StockRequest(productCodeIdempotent, 50)  // 超库存
        );
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
                .containsExactlyInAnyOrder(productCodeIdempotent);
    }

    // ------------------ 批量并发 + 幂等组合测试 ------------------
    @Test
    void testBatchConcurrentAndIdempotent() throws Exception {
        int threadCount = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        List<Future<SimpleResponse<Object>>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final long orderId = 2000L + i; // 幂等 orderId
            futures.add(executor.submit(() -> {
                try {
                    List<StockRequest> requests = List.of(
                            new StockRequest(productCodeConcurrent, 20),
                            new StockRequest(productCodeIdempotent, 15)
                    );
                    InventoryBatchRequest event = new InventoryBatchRequest(orderId, requests);
                    MvcResult result = mockMvc.perform(post("/inventories/batch/lock")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(event)))
                            .andReturn();
                    return objectMapper.readValue(result.getResponse().getContentAsString(),
                            new TypeReference<SimpleResponse<Object>>() {});
                } finally {
                    latch.countDown();
                }
            }));
        }

        latch.await();
        executor.shutdown();

        Inventory inventoryConcurrent = inventoryRepository.findInventoryByProductCode(productCodeConcurrent).orElseThrow();
        Inventory inventoryIdempotent = inventoryRepository.findInventoryByProductCode(productCodeIdempotent).orElseThrow();

        // 校验总库存没有超卖
        assertThat(inventoryConcurrent.getAvailableStock() + inventoryConcurrent.getLockedStock()).isEqualTo(50);
        assertThat(inventoryIdempotent.getAvailableStock() + inventoryIdempotent.getLockedStock()).isEqualTo(20);
    }
}
