package org.example.inventoryservice;

import java.util.ArrayList;
import java.util.concurrent.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.dto.StockRequest;
import org.common.product.dto.InventoryBatchCheckResult;
import org.example.inventoryservice.config.RedisKeys;
import org.example.inventoryservice.domain.Inventory;
import org.example.inventoryservice.dto.*;
import org.example.inventoryservice.repository.InventoryOperationRepository;
import org.example.inventoryservice.repository.InventoryRepository;
import org.example.inventoryservice.service.InventoryIdempotencyExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Set;

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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private final Long productCodeConcurrent = 1001L;
    private final Long productCodeIdempotent = 1002L;

    @BeforeEach
    void initInventory() {
        // 1️⃣ Redis 幂等 key 清理
        Set<String> idempotencyKeys = stringRedisTemplate.keys(InventoryIdempotencyExecutor.IDEM_PREFIX + "*");
        if (idempotencyKeys != null && !idempotencyKeys.isEmpty()) {
            stringRedisTemplate.delete(idempotencyKeys);
        }

        // Clean Redis stock keys
        Set<String> stockKeys = stringRedisTemplate.keys("inventory:stock:*");
        if (stockKeys != null && !stockKeys.isEmpty()) {
            stringRedisTemplate.delete(stockKeys);
        }

        // 2️⃣ 先删除操作记录
        inventoryOperationRepository.deleteAll();
        inventoryOperationRepository.flush();

        // 3️⃣ 再删除库存记录
        inventoryRepository.deleteAll();
        inventoryRepository.flush();

        // 4️⃣ 初始化测试数据
        inventoryRepository.saveAll(List.of(
                new Inventory(productCodeConcurrent, 50),
                new Inventory(productCodeIdempotent, 20)
        ));

        // 5️⃣ 初始化 Redis available stock
        stringRedisTemplate.opsForValue().set(
                RedisKeys.availableStockKey(productCodeConcurrent), "50");
        stringRedisTemplate.opsForValue().set(
                RedisKeys.availableStockKey(productCodeIdempotent), "20");
    }

    // ------------------ 并发锁定库存 ------------------
    @Test
    void testConcurrentStockLock() throws Exception {
        int threadCount = 5;
        int quantityPerThread = 20;
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

        // 校验 Redis 库存总量没有超卖 (only one thread can lock 20)
        String availStr = stringRedisTemplate.opsForValue().get(
                RedisKeys.availableStockKey(productCodeConcurrent));
        String lockedStr = stringRedisTemplate.opsForValue().get(
                RedisKeys.lockedStockKey(productCodeConcurrent));
        long avail = availStr == null ? 0 : Long.parseLong(availStr);
        long locked = lockedStr == null ? 0 : Long.parseLong(lockedStr);
        assertThat(avail + locked).isEqualTo(50);
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

        // Redis locked should be 10
        String lockedStr = stringRedisTemplate.opsForValue().get(
                RedisKeys.lockedStockKey(productCodeIdempotent));
        assertThat(lockedStr).isEqualTo("10");

        // 重复请求
        MvcResult result2 = mockMvc.perform(post("/inventories/batch/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andReturn();
        SimpleResponse<Object> response2 = objectMapper.readValue(
                result2.getResponse().getContentAsString(),
                new TypeReference<SimpleResponse<Object>>() {}
        );
        assertThat(response2.isSuccess()).isTrue();

        // Redis locked should still be 10
        lockedStr = stringRedisTemplate.opsForValue().get(
                RedisKeys.lockedStockKey(productCodeIdempotent));
        assertThat(lockedStr).isEqualTo("10");

        // 第三次重复请求
        MvcResult result3 = mockMvc.perform(post("/inventories/batch/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andReturn();
        SimpleResponse<Object> response3 = objectMapper.readValue(
                result3.getResponse().getContentAsString(),
                new TypeReference<SimpleResponse<Object>>() {}
        );
        assertThat(response3.isSuccess()).isTrue();

        lockedStr = stringRedisTemplate.opsForValue().get(
                RedisKeys.lockedStockKey(productCodeIdempotent));
        assertThat(lockedStr).isEqualTo("10");
    }

    // ------------------ 批量库存部分可用测试 ------------------
    @Test
    void testBatchCheckStock_partialAvailable() throws Exception {
        List<StockRequest> stockRequestsPartial = List.of(
                new StockRequest(productCodeConcurrent, 30), // avail=50, ok
                new StockRequest(productCodeIdempotent, 50)  // avail=20, fail
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
            final long orderId = 2000L + i;
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

        // Consumer 1001: avail=50, each thread tries 20 → only 2 can succeed (20+20=40 <= 50)
        // Consumer 1002: avail=20, each thread tries 15 → only 1 can succeed
        // So only 1 thread succeeds fully for both products simultaneously.

        // Redis locked + avail should equal 50 for 1001 and 20 for 1002
        String availStr1 = stringRedisTemplate.opsForValue().get(
                RedisKeys.availableStockKey(productCodeConcurrent));
        String lockedStr1 = stringRedisTemplate.opsForValue().get(
                RedisKeys.lockedStockKey(productCodeConcurrent));
        long avail1 = availStr1 == null ? 0 : Long.parseLong(availStr1);
        long locked1 = lockedStr1 == null ? 0 : Long.parseLong(lockedStr1);
        assertThat(avail1 + locked1).isEqualTo(50);

        String availStr2 = stringRedisTemplate.opsForValue().get(
                RedisKeys.availableStockKey(productCodeIdempotent));
        String lockedStr2 = stringRedisTemplate.opsForValue().get(
                RedisKeys.lockedStockKey(productCodeIdempotent));
        long avail2 = availStr2 == null ? 0 : Long.parseLong(availStr2);
        long locked2 = lockedStr2 == null ? 0 : Long.parseLong(lockedStr2);
        assertThat(avail2 + locked2).isEqualTo(20);
    }
}