package org.example.inventoryservice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.dto.StockRequest;
import org.example.inventoryservice.config.RedisKeys;
import org.example.inventoryservice.domain.Inventory;
import org.example.inventoryservice.domain.InventoryLog;
import org.example.inventoryservice.domain.OperationType;
import org.example.inventoryservice.dto.SimpleResponse;
import org.example.inventoryservice.repository.InventoryLogRepository;
import org.example.inventoryservice.repository.InventoryOperationRepository;
import org.example.inventoryservice.repository.InventoryRepository;
import org.example.inventoryservice.service.InventoryDomainService;
import org.example.inventoryservice.service.InventoryIdempotencyExecutor;
import org.example.inventoryservice.service.InventoryService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("InventoryLog 日志记录全路径测试")
public class InventoryLogApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryLogRepository inventoryLogRepository;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryDomainService inventoryDomainService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private InventoryOperationRepository inventoryOperationRepository;

    @BeforeEach
    void setUp() {
        // Clean Redis idempotency keys
        Set<String> idemKeys = stringRedisTemplate.keys(InventoryIdempotencyExecutor.IDEM_PREFIX + "*");
        if (idemKeys != null) stringRedisTemplate.delete(idemKeys);
        // Clean Redis stock keys
        Set<String> stockKeys = stringRedisTemplate.keys("inventory:stock:*");
        if (stockKeys != null) stringRedisTemplate.delete(stockKeys);

        inventoryOperationRepository.deleteAll();
        inventoryLogRepository.deleteAll();
        inventoryRepository.deleteAll();

        // 准备测试库存（除了 create 测试外，其他测试都依赖已有库存）
        List<Inventory> inventories = List.of(
                new Inventory(1001L, 50),
                new Inventory(1002L, 100)
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

    // ==================== Repository 层基础测试 ====================

    @Nested
    @DisplayName("Repository 层基础操作")
    class RepositoryTests {

        @Test
        @DisplayName("保存单条 InventoryLog 记录")
        void saveSingleLog() {
            InventoryLog log = new InventoryLog(1001L, 10, 1L, OperationType.LOCK);
            inventoryLogRepository.save(log);

            List<InventoryLog> all = inventoryLogRepository.findAll();
            assertThat(all).hasSize(1);
            InventoryLog saved = all.get(0);
            assertThat(saved.getProductCode()).isEqualTo(1001L);
            assertThat(saved.getQuantity()).isEqualTo(10);
            assertThat(saved.getOrderId()).isEqualTo(1L);
            assertThat(saved.getOperationType()).isEqualTo(OperationType.LOCK);
        }

        @Test
        @DisplayName("保存 admin 操作日志（orderId 为 null）")
        void saveAdminLogWithNullOrderId() {
            InventoryLog log = new InventoryLog(1001L, 5, null, OperationType.MANUAL_DEDUCT);
            inventoryLogRepository.save(log);

            InventoryLog saved = inventoryLogRepository.findAll().get(0);
            assertThat(saved.getOrderId()).isNull();
            assertThat(saved.getOperationType()).isEqualTo(OperationType.MANUAL_DEDUCT);
        }

        @Test
        @DisplayName("@PrePersist 自动填充 createdAt")
        void createdAtAutoFilled() {
            InventoryLog log = new InventoryLog(1001L, 5, null, OperationType.MANUAL_ADD);
            inventoryLogRepository.save(log);

            InventoryLog saved = inventoryLogRepository.findAll().get(0);
            assertThat(saved.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("saveAll 批量保存多条记录")
        void saveAllBatch() {
            List<InventoryLog> logs = List.of(
                    new InventoryLog(1001L, 10, 1L, OperationType.LOCK),
                    new InventoryLog(1002L, 20, 1L, OperationType.LOCK)
            );
            inventoryLogRepository.saveAll(logs);

            List<InventoryLog> all = inventoryLogRepository.findAll();
            assertThat(all).hasSize(2);
        }
    }

    // ==================== Admin API — create / deduct / add ====================

    @Nested
    @DisplayName("Admin 接口日志记录")
    class AdminApiLogTests {

        @Test
        @DisplayName("POST /admin/inventories/create → 创建库存 + inventory_log (MANUAL_CREATE)")
        void createStock_shouldCreateLog() throws Exception {
            StockRequest request = new StockRequest(2001L, 100);

            mockMvc.perform(post("/admin/inventories/create")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());

            // 验证 inventory 表
            assertThat(inventoryRepository.findInventoryByProductCode(2001L))
                    .isPresent()
                    .hasValueSatisfying(inv -> {
                        assertThat(inv.getOnHandStock()).isEqualTo(100);
                    });

            // 验证 inventory_log 表
            List<InventoryLog> logs = inventoryLogRepository.findAll();
            assertThat(logs).hasSize(1);
            InventoryLog log = logs.get(0);
            assertThat(log.getProductCode()).isEqualTo(2001L);
            assertThat(log.getQuantity()).isEqualTo(100);
            assertThat(log.getOrderId()).isNull();
            assertThat(log.getOperationType()).isEqualTo(OperationType.MANUAL_CREATE);
            assertThat(log.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("POST /admin/inventories/create → 重复创建抛异常，日志不写入")
        void createStock_duplicate_shouldRollbackLog() {
            inventoryService.createStock(2002L, 50);

            assertThatThrownBy(() -> inventoryService.createStock(2002L, 50))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Product already exists");

            // 日志只有第一条创建的记录
            List<InventoryLog> logs = inventoryLogRepository.findAll();
            assertThat(logs).hasSize(1);
            assertThat(logs.get(0).getOperationType()).isEqualTo(OperationType.MANUAL_CREATE);
        }

        @Test
        @DisplayName("POST /admin/inventories/deduct → 扣减库存 + inventory_log (MANUAL_DEDUCT)")
        void deductStock_shouldCreateLog() throws Exception {
            StockRequest request = new StockRequest(1001L, 2);

            mockMvc.perform(post("/admin/inventories/deduct")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());

            // 验证库存已扣减
            Inventory inventory = inventoryRepository.findInventoryByProductCode(1001L).get();
            assertThat(inventory.getOnHandStock()).isEqualTo(48);

            // 验证日志
            List<InventoryLog> logs = inventoryLogRepository.findAll();
            assertThat(logs).hasSize(1);
            InventoryLog log = logs.get(0);
            assertThat(log.getProductCode()).isEqualTo(1001L);
            assertThat(log.getQuantity()).isEqualTo(2);
            assertThat(log.getOrderId()).isNull();
            assertThat(log.getOperationType()).isEqualTo(OperationType.MANUAL_DEDUCT);
        }

        @Test
        @DisplayName("POST /admin/inventories/add → 增加库存 + inventory_log (MANUAL_ADD)")
        void addStock_shouldCreateLog() throws Exception {
            StockRequest request = new StockRequest(1002L, 10);

            mockMvc.perform(post("/admin/inventories/add")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());

            // 验证库存已增加
            Inventory inventory = inventoryRepository.findInventoryByProductCode(1002L).get();
            assertThat(inventory.getOnHandStock()).isEqualTo(110);

            // 验证日志
            List<InventoryLog> logs = inventoryLogRepository.findAll();
            assertThat(logs).hasSize(1);
            InventoryLog log = logs.get(0);
            assertThat(log.getProductCode()).isEqualTo(1002L);
            assertThat(log.getQuantity()).isEqualTo(10);
            assertThat(log.getOrderId()).isNull();
            assertThat(log.getOperationType()).isEqualTo(OperationType.MANUAL_ADD);
        }

        @Test
        @DisplayName("连续 deduct 和 add → 生成两条独立日志")
        void multipleAdminOps_shouldCreateMultipleLogs() throws Exception {
            mockMvc.perform(post("/admin/inventories/deduct")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productCode\": 1001, \"quantity\": 5}"))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/admin/inventories/add")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productCode\": 1002, \"quantity\": 3}"))
                    .andExpect(status().isOk());

            List<InventoryLog> logs = inventoryLogRepository.findAll();
            assertThat(logs).hasSize(2);

            assertThat(logs).anySatisfy(log ->
                    assertThat(log.getOperationType()).isEqualTo(OperationType.MANUAL_DEDUCT));
            assertThat(logs).anySatisfy(log ->
                    assertThat(log.getOperationType()).isEqualTo(OperationType.MANUAL_ADD));
        }
    }

    // ==================== 幂等操作日志 ====================

    @Nested
    @DisplayName("幂等操作日志记录 (LOCK/CONFIRM/UNLOCK)")
    class IdempotentOpLogTests {

        @Test
        @DisplayName("batchLockStock 锁定库存 → 生成 LOCK 日志（saveAll 批量写入）")
        void batchLockStock_shouldCreateLog() throws Exception {
            InventoryBatchRequest request = new InventoryBatchRequest(
                    100L, List.of(
                    new StockRequest(1001L, 5),
                    new StockRequest(1002L, 10)
            ));

            mockMvc.perform(post("/inventories/batch/lock")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());

            // 验证库存已锁定（DB onHandStock 不变，Redis avail/locked 变化）
            Inventory inv1001 = inventoryRepository.findInventoryByProductCode(1001L).get();
            assertThat(inv1001.getOnHandStock()).isEqualTo(50);  // DB 不变

            Inventory inv1002 = inventoryRepository.findInventoryByProductCode(1002L).get();
            assertThat(inv1002.getOnHandStock()).isEqualTo(100);  // DB 不变

            // 验证日志: 两条 LOCK 记录，orderId=100
            List<InventoryLog> logs = inventoryLogRepository.findAll();
            assertThat(logs).hasSize(2);

            assertThat(logs).allSatisfy(log -> {
                assertThat(log.getOrderId()).isEqualTo(100L);
                assertThat(log.getOperationType()).isEqualTo(OperationType.LOCK);
                assertThat(log.getCreatedAt()).isNotNull();
            });

            // 验证各 productCode 的日志都存在
            assertThat(logs).anySatisfy(log ->
                    assertThat(log.getProductCode()).isEqualTo(1001L));
            assertThat(logs).anySatisfy(log ->
                    assertThat(log.getProductCode()).isEqualTo(1002L));
        }

        @Test
        @DisplayName("batchConfirmSale 确认销售 → 生成 CONFIRM 日志")
        void batchConfirmSale_shouldCreateLog() {
            Long orderId = 200L;

            // 先 lock（通过幂等执行器）
            inventoryDomainService.batchLockStock(orderId, List.of(
                    new StockRequest(1001L, 5),
                    new StockRequest(1002L, 10)
            ));

            // 清除 lock 产生的日志，只关心 confirm 的日志
            inventoryLogRepository.deleteAll();

            // 执行 confirm（通过幂等执行器）
            inventoryDomainService.batchConfirmSale(orderId, List.of(
                    new StockRequest(1001L, 5),
                    new StockRequest(1002L, 10)
            ));

            // 验证库存
            Inventory inv1001 = inventoryRepository.findInventoryByProductCode(1001L).get();
            assertThat(inv1001.getSoldStock()).isEqualTo(5);
            assertThat(inv1001.getOnHandStock()).isEqualTo(45);

            // 验证日志
            List<InventoryLog> logs = inventoryLogRepository.findAll();
            assertThat(logs).hasSize(2);
            assertThat(logs).allSatisfy(log -> {
                assertThat(log.getOrderId()).isEqualTo(orderId);
                assertThat(log.getOperationType()).isEqualTo(OperationType.CONFIRM);
            });
        }

        @Test
        @DisplayName("batchUnlockStock 解锁库存 → 生成 UNLOCK 日志")
        void batchUnlockStock_shouldCreateLog() {
            Long orderId = 300L;

            // 先 lock
            inventoryDomainService.batchLockStock(orderId, List.of(
                    new StockRequest(1001L, 3)
            ));

            // 清除 lock 产生的日志
            inventoryLogRepository.deleteAll();

            // 执行 unlock（通过幂等执行器）
            inventoryDomainService.batchUnlockStock(orderId, List.of(
                    new StockRequest(1001L, 3)
            ));

            // 验证库存：DB onHandStock 不变（UNLOCK 只操作 Redis）
            Inventory inv = inventoryRepository.findInventoryByProductCode(1001L).get();
            assertThat(inv.getOnHandStock()).isEqualTo(50);

            // 验证日志
            List<InventoryLog> logs = inventoryLogRepository.findAll();
            assertThat(logs).hasSize(1);
            InventoryLog log = logs.get(0);
            assertThat(log.getProductCode()).isEqualTo(1001L);
            assertThat(log.getQuantity()).isEqualTo(3);
            assertThat(log.getOrderId()).isEqualTo(orderId);
            assertThat(log.getOperationType()).isEqualTo(OperationType.UNLOCK);
        }

        @Test
        @DisplayName("LOCK+CONFIRM+UNLOCK 完整链路 → 每种操作都有对应日志")
        void fullLifecycle_shouldCreateAllLogs() {
            Long orderId = 400L;

            // Step 1: Lock
            inventoryDomainService.batchLockStock(orderId, List.of(
                    new StockRequest(1001L, 10)
            ));

            // Step 2: Confirm
            inventoryDomainService.batchConfirmSale(orderId, List.of(
                    new StockRequest(1001L, 5)
            ));

            // Step 3: Unlock 剩余的 5
            inventoryDomainService.batchUnlockStock(orderId, List.of(
                    new StockRequest(1001L, 5)
            ));

            // 验证 3 条日志：LOCK, CONFIRM, UNLOCK
            List<InventoryLog> logs = inventoryLogRepository.findAll();
            // 这里因为 LOCK 和 CONFIRM 各产生了1条日志，UNLOCK 产生了1条
            // 但 LOCK 时 lock(10)，CONFIRM 时 confirmSale(5)，UNLOCK 时 unlock(5)
            // 注意 confirmSale 只减少 lockedStock，UNLOCK 只减少 lockedStock
            // 所以 confirmSale(5) 后 lockedStock=5，unlock(5) 后 lockedStock=0

            // 验证每种操作类型都有记录
            assertThat(logs).anySatisfy(log ->
                    assertThat(log.getOperationType()).isEqualTo(OperationType.LOCK));
            assertThat(logs).anySatisfy(log ->
                    assertThat(log.getOperationType()).isEqualTo(OperationType.CONFIRM));
            assertThat(logs).anySatisfy(log ->
                    assertThat(log.getOperationType()).isEqualTo(OperationType.UNLOCK));

            // 验证最终库存状态
            Inventory inv = inventoryRepository.findInventoryByProductCode(1001L).get();
            assertThat(inv.getOnHandStock()).isEqualTo(45);
            assertThat(inv.getSoldStock()).isEqualTo(5);
        }
    }

    // ==================== 事务回滚测试 ====================

    @Nested
    @DisplayName("事务回滚场景 — 日志不写入")
    class TransactionRollbackTests {

        @Test
        @DisplayName("扣减库存超量 → 异常回滚，日志不写入")
        void deductExceedStock_shouldRollbackLog() {
            // 1001 只有 50 库存，扣减 100 应失败
            assertThatThrownBy(() -> inventoryService.deductStockDirectly(1001L, 100))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("out of stock");

            // 库存未变化
            Inventory inv = inventoryRepository.findInventoryByProductCode(1001L).get();
            assertThat(inv.getOnHandStock()).isEqualTo(50);

            // 日志未写入
            assertThat(inventoryLogRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("对不存在的商品扣减 → 异常回滚，日志不写入")
        void deductNonExistentProduct_shouldRollbackLog() {
            assertThatThrownBy(() -> inventoryService.deductStockDirectly(9999L, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Product not found");

            assertThat(inventoryLogRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("batchLockStock 商品不存在 → 异常回滚，无日志写入")
        void batchLockNonExistentProduct_shouldRollbackLog() {
            Long orderId = 500L;

            assertThatThrownBy(() -> inventoryDomainService.batchLockStock(orderId, List.of(
                    new StockRequest(1001L, 5),
                    new StockRequest(9999L, 10) // 不存在的商品
            ))).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Product not found");

            // 事务回滚：1001 的库存应恢复原值，log 表无记录
            Inventory inv = inventoryRepository.findInventoryByProductCode(1001L).get();
            assertThat(inv.getOnHandStock()).isEqualTo(50);
            assertThat(inventoryLogRepository.findAll()).isEmpty();
        }
    }
}