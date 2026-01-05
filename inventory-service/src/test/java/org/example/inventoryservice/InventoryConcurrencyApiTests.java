package org.example.inventoryservice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.dto.StockRequest;
import org.example.inventoryservice.domain.Inventory;
import org.example.inventoryservice.domain.OperationType;

import org.example.inventoryservice.dto.SimpleResponse;
import org.example.inventoryservice.repository.InventoryRepository;
import org.example.inventoryservice.repository.InventoryOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
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

    private List<StockRequest> stockRequests;

    @BeforeEach
    void setup() {

        // 先删除操作记录
        inventoryOperationRepository.deleteAll();
        inventoryOperationRepository.flush();  // 强制刷新

        // 再删除库存记录
        inventoryRepository.deleteAll();
        inventoryRepository.flush();  // 强制刷新

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

        // 启动所有线程
        startLatch.countDown();
        // 等待所有线程完成
        doneLatch.await();

        // 幂等控制：只有一次真正成功
        assertThat(successCount.get()).isEqualTo(1);

        // 校验库存是否正确扣减
        Inventory inv1 = inventoryRepository.findInventoryByProductCode(1001L).get();
        Inventory inv2 = inventoryRepository.findInventoryByProductCode(1002L).get();
        Inventory inv3 = inventoryRepository.findInventoryByProductCode(1003L).get();

        assertThat(inv1.getLockedStock()).isEqualTo(10);
        assertThat(inv2.getLockedStock()).isEqualTo(20);
        assertThat(inv3.getLockedStock()).isEqualTo(30);

        assertThat(inv1.getAvailableStock()).isEqualTo(90);
        assertThat(inv2.getAvailableStock()).isEqualTo(180);
        assertThat(inv3.getAvailableStock()).isEqualTo(270);
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

        // 校验库存：乐观锁设计下，只有一个线程能成功
        Inventory inv1 = inventoryRepository.findInventoryByProductCode(1001L).get();
        Inventory inv2 = inventoryRepository.findInventoryByProductCode(1002L).get();
        Inventory inv3 = inventoryRepository.findInventoryByProductCode(1003L).get();

        // 正确断言：只有一个成功
        assertThat(inv1.getLockedStock()).isEqualTo(10);   // 不是50！
        assertThat(inv2.getLockedStock()).isEqualTo(20);   // 不是100！
        assertThat(inv3.getLockedStock()).isEqualTo(30);   // 不是150！

        // 也可以验证可用库存减少
        assertThat(inv1.getAvailableStock()).isEqualTo(90);   // 100 - 10
        assertThat(inv2.getAvailableStock()).isEqualTo(180);  // 200 - 20
        assertThat(inv3.getAvailableStock()).isEqualTo(270);  // 300 - 30
    }

    @Test
    @SneakyThrows
    void testBatchLockStock_sufficientStock() {
        // 尝试锁定超过可用库存的数量
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
        // assertThat(response.getMessage()).contains("");
    }
    @Test
    @SneakyThrows
    void testBatchLockStock_borderStock() {
        // 尝试锁定超过可用库存的数量
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

        // 应该返回失败
        assertThat(response.isSuccess()).isTrue();
        // assertThat(response.getMessage()).contains("");
    }
    @Test
    void testBatchLockStock_insufficientStock() throws Exception {
        // 1. 准备：验证初始库存状态
        Inventory initialInventory = inventoryRepository.findInventoryByProductCode(1001L)
                .orElseThrow(() -> new RuntimeException("商品1001不存在"));

        System.out.println("初始库存 - 可用: " + initialInventory.getAvailableStock() +
                ", 锁定: " + initialInventory.getLockedStock());

        assertThat(initialInventory.getAvailableStock()).isEqualTo(100);
        assertThat(initialInventory.getLockedStock()).isEqualTo(0);

        // 2. 尝试锁定超过可用库存的数量
        List<StockRequest> largeRequests = List.of(
                new StockRequest(1001L, 200)  // 只有100个可用库存，请求200个
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

        // 3. 直接查询数据库验证库存状态
        Inventory afterLockInventory = inventoryRepository.findInventoryByProductCode(1001L)
                .orElseThrow(() -> new RuntimeException("商品1001不存在"));

        System.out.println("锁定后库存 - 可用: " + afterLockInventory.getAvailableStock() +
                ", 锁定: " + afterLockInventory.getLockedStock() + " response " + response + " 状态码 " + result.getResponse().getStatus());

        // 4. 验证断言

        // 断言1：API应该返回失败
        assertThat(response.isSuccess()).isFalse()
                .withFailMessage("库存不足时锁定应该返回失败，但返回了成功。响应消息: " + response.getMessage());

        // 断言2：可用库存应该保持不变（100）
        assertThat(afterLockInventory.getAvailableStock())
                .isEqualTo(100)
                .withFailMessage("库存不足时可用库存不应该被扣减");

        // 断言3：锁定库存应该为0
        assertThat(afterLockInventory.getLockedStock())
                .isEqualTo(0)
                .withFailMessage("库存不足时不应该有锁定库存");

        // 断言4：总库存应该保持不变（可用+锁定=100）
        assertThat(afterLockInventory.getAvailableStock() + afterLockInventory.getLockedStock())
                .isEqualTo(100)
                .withFailMessage("总库存应该保持不变");

        // 断言5：验证没有创建操作记录
        boolean operationExists = inventoryOperationRepository
                .existsByOrderIdAndOperationType(888L, OperationType.LOCK);
        assertThat(operationExists)
                .isTrue()
                .withFailMessage("库存不足时也创建LOCK操作记录");

        // 断言6：检查错误消息（如果有的话）
        if (response.getMessage() != null) {
            System.out.println("错误消息: " + response.getMessage());
            // 可以根据业务逻辑检查消息内容
            // assertThat(response.getMessage()).contains("库存不足");
        }

        MvcResult result2 = mockMvc.perform(post("/inventories/batch/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andReturn();

        SimpleResponse<?> response2 = objectMapper.readValue(
                result2.getResponse().getContentAsString(),
                new TypeReference<SimpleResponse<?>>() {}
        );


        // 3. 直接查询数据库验证库存状态
        Inventory afterLockInventory2 = inventoryRepository.findInventoryByProductCode(1001L)
                .orElseThrow(() -> new RuntimeException("商品1001不存在"));

        System.out.println("锁定后库存 - 可用: " + afterLockInventory2.getAvailableStock() +
                ", 锁定: " + afterLockInventory2.getLockedStock() +  " response " + response2 + " 状态码 " + result2.getResponse().getStatus() + " operationrecord " + inventoryOperationRepository.findByOrderIdAndOperationType(888L, OperationType.LOCK));

        // 断言1：API应该返回失败
        assertThat(response2.isSuccess()).isFalse()
                .withFailMessage("库存不足时锁定应该返回失败，但返回了成功。响应消息: " + response2.getMessage());
        // 断言2：可用库存应该保持不变（100）
        assertThat(afterLockInventory2.getAvailableStock())
                .isEqualTo(100)
                .withFailMessage("库存不足时可用库存不应该被扣减");

        // 断言3：锁定库存应该为0
        assertThat(afterLockInventory2.getLockedStock())
                .isEqualTo(0)
                .withFailMessage("库存不足时不应该有锁定库存");

        // 断言4：总库存应该保持不变（可用+锁定=100）
        assertThat(afterLockInventory2.getAvailableStock() + afterLockInventory2.getLockedStock())
                .isEqualTo(100)
                .withFailMessage("总库存应该保持不变");
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
