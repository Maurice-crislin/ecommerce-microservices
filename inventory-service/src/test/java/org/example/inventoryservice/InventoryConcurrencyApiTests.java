package org.example.inventoryservice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.dto.StockRequest;
import org.example.inventoryservice.config.RedisKeys;
import org.example.inventoryservice.domain.Inventory;
import org.example.inventoryservice.domain.OperationType;

import org.example.inventoryservice.dto.SimpleResponse;
import org.example.inventoryservice.repository.InventoryRepository;
import org.example.inventoryservice.repository.InventoryOperationRepository;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
public class InventoryConcurrencyApiTests {

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

    private List<StockRequest> stockRequests;

    @BeforeEach
    void setup() {

        // 0️⃣ Redis 幂等 key 清理
        Set<String> idempotencyKeys = stringRedisTemplate.keys(InventoryIdempotencyExecutor.IDEM_PREFIX + "*");
        if (idempotencyKeys != null && !idempotencyKeys.isEmpty()) {
            stringRedisTemplate.delete(idempotencyKeys);
        }

        // Clean Redis stock keys
        Set<String> stockKeys = stringRedisTemplate.keys("inventory:stock:*");
        if (stockKeys != null && !stockKeys.isEmpty()) {
            stringRedisTemplate.delete(stockKeys);
        }

        // 1️⃣ 先删除操作记录
        inventoryOperationRepository.deleteAll();
        inventoryOperationRepository.flush();

        // 2️⃣ 再删除库存记录
        inventoryRepository.deleteAll();
        inventoryRepository.flush();

        stockRequests = List.of(
                new StockRequest(1001L, 10),
                new StockRequest(1002L, 20),
                new StockRequest(1003L, 30)
        );

        List<Inventory> inventories = List.of(
                new Inventory(1001L, 100),
                new Inventory(1002L, 200),
                new Inventory(1003L, 300)
        );

        inventoryRepository.saveAll(inventories);

        // 初始化 Redis available stock
        for (Inventory inv : inventories) {
            stringRedisTemplate.opsForValue().set(
                    RedisKeys.availableStockKey(inv.getProductCode()),
                    String.valueOf(inv.getOnHandStock())
            );
        }
    }

    // ----------------- 并发 batch/lock 测试 -----------------
    @Test
    @SneakyThrows
    void testConcurrentBatchLockStock() {
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();

        long orderId = 999L;

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();

                    InventoryBatchRequest event = new InventoryBatchRequest(orderId, stockRequests);

                    MvcResult result = mockMvc.perform(post("/inventories/batch/lock")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(event)))
                            .andReturn();

                    SimpleResponse<?> response = objectMapper.readValue(
                            result.getResponse().getContentAsString(),
                            new TypeReference<SimpleResponse<?>>() {}
                    );

                    if (response.isSuccess()) {
                        successCount.incrementAndGet();
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await();

        // 幂等控制：只有一次真正成功
        assertThat(successCount.get()).isEqualTo(1);

        // DB onHandStock 不变 (LOCK doesn't touch DB)
        Inventory inv1 = inventoryRepository.findInventoryByProductCode(1001L).get();
        Inventory inv2 = inventoryRepository.findInventoryByProductCode(1002L).get();
        Inventory inv3 = inventoryRepository.findInventoryByProductCode(1003L).get();

        assertThat(inv1.getOnHandStock()).isEqualTo(100);
        assertThat(inv2.getOnHandStock()).isEqualTo(200);
        assertThat(inv3.getOnHandStock()).isEqualTo(300);
    }

    @Test
    @SneakyThrows
    void testConcurrentBatchLockStock_differentOrderIds() {
        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final long orderId = 1000L + i;
            new Thread(() -> {
                try {
                    startLatch.await();

                    InventoryBatchRequest event = new InventoryBatchRequest(orderId, stockRequests);

                    mockMvc.perform(post("/inventories/batch/lock")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(event)))
                            .andReturn();

                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await();

        // DB 不变（LOCK 只操作 Redis）
        Inventory inv1 = inventoryRepository.findInventoryByProductCode(1001L).get();
        Inventory inv2 = inventoryRepository.findInventoryByProductCode(1002L).get();
        Inventory inv3 = inventoryRepository.findInventoryByProductCode(1003L).get();

        assertThat(inv1.getOnHandStock()).isEqualTo(100);
        assertThat(inv2.getOnHandStock()).isEqualTo(200);
        assertThat(inv3.getOnHandStock()).isEqualTo(300);
    }

    @Test
    @SneakyThrows
    void testBatchLockStock_sufficientStock() {
        List<StockRequest> largeRequests = List.of(
                new StockRequest(1001L, 50)
        );

        InventoryBatchRequest event = new InventoryBatchRequest(888L, largeRequests);

        MvcResult result = mockMvc.perform(post("/inventories/batch/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andReturn();

        SimpleResponse<?> response = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                new TypeReference<SimpleResponse<?>>() {}
        );

        assertThat(response.isSuccess()).isTrue();
    }

    @Test
    @SneakyThrows
    void testBatchLockStock_borderStock() {
        List<StockRequest> largeRequests = List.of(
                new StockRequest(1001L, 100)
        );

        InventoryBatchRequest event = new InventoryBatchRequest(888L, largeRequests);

        MvcResult result = mockMvc.perform(post("/inventories/batch/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andReturn();

        SimpleResponse<?> response = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                new TypeReference<SimpleResponse<?>>() {}
        );

        // 锁定全部库存应成功
        assertThat(response.isSuccess()).isTrue();
    }

    @Test
    void testBatchLockStock_insufficientStock() throws Exception {
        // 1. 验证初始库存状态
        Inventory initialInventory = inventoryRepository.findInventoryByProductCode(1001L)
                .orElseThrow(() -> new RuntimeException("商品1001不存在"));
        assertThat(initialInventory.getOnHandStock()).isEqualTo(100);

        // 2. 尝试锁定超过可用库存的数量
        List<StockRequest> largeRequests = List.of(
                new StockRequest(1001L, 200)
        );

        InventoryBatchRequest event = new InventoryBatchRequest(888L, largeRequests);

        MvcResult result = mockMvc.perform(post("/inventories/batch/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andReturn();

        SimpleResponse<?> response = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                new TypeReference<SimpleResponse<?>>() {}
        );

        // 3. API应该返回失败
        assertThat(response.isSuccess()).isFalse()
                .withFailMessage("库存不足时锁定应该返回失败");

        // 4. onHand库存应该保持不变（100）
        Inventory afterLockInventory = inventoryRepository.findInventoryByProductCode(1001L)
                .orElseThrow(() -> new RuntimeException("商品1001不存在"));
        assertThat(afterLockInventory.getOnHandStock())
                .isEqualTo(100)
                .withFailMessage("库存不足时onHand库存不应该被扣减");

        // 5. 验证创建了LOCK操作记录 (幂等执行器在业务失败前已创建)
        boolean operationExists = inventoryOperationRepository
                .existsByOrderIdAndOperationType(888L, OperationType.LOCK);
        assertThat(operationExists)
                .isTrue()
                .withFailMessage("库存不足时也应创建LOCK操作记录");

        // 6. 重复请求
        MvcResult result2 = mockMvc.perform(post("/inventories/batch/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andReturn();

        SimpleResponse<?> response2 = objectMapper.readValue(
                result2.getResponse().getContentAsString(),
                new TypeReference<SimpleResponse<?>>() {}
        );

        Inventory afterLockInventory2 = inventoryRepository.findInventoryByProductCode(1001L)
                .orElseThrow(() -> new RuntimeException("商品1001不存在"));

        assertThat(response2.isSuccess()).isFalse()
                .withFailMessage("库存不足时锁定应该返回失败，但返回了成功");
        assertThat(afterLockInventory2.getOnHandStock())
                .isEqualTo(100)
                .withFailMessage("库存不足时onHand库存不应该被扣减");
    }

    @Test
    @SneakyThrows
    void testBatchLockStock_emptyRequest() {
        // 空商品列表
        InventoryBatchRequest event = new InventoryBatchRequest(777L, List.of());

        MvcResult result = mockMvc.perform(post("/inventories/batch/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andReturn();

        SimpleResponse<?> response = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                new TypeReference<SimpleResponse<?>>() {}
        );

        assertThat(response.isSuccess()).isFalse();
    }
}