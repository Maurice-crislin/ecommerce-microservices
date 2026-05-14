package org.example.inventoryservice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.dto.StockRequest;
import org.common.product.dto.InventoryBatchCheckResult;
import org.example.inventoryservice.config.RedisKeys;
import org.example.inventoryservice.domain.*;
import org.example.inventoryservice.dto.SimpleResponse;
import org.example.inventoryservice.repository.InventoryLogRepository;
import org.example.inventoryservice.repository.InventoryOperationRepository;
import org.example.inventoryservice.repository.InventoryRepository;
import org.example.inventoryservice.service.InventoryDomainService;
import org.example.inventoryservice.service.InventoryIdempotencyExecutor;
import org.example.inventoryservice.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 库存核心流程全路径测试（Redis + DB）
 * 适配 onHandStock/soldStock schema: LOCK/CONFIRM/UNLOCK 只操作 Redis,
 * CONFIRM 额外修改 DB (onHandStock--, soldStock++), UNLOCK/LOCK 不修改 DB.
 * 恒等式: onHandStock = Redis avail + Redis locked
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("库存核心流程全路径测试（Redis + DB）")
public class InventoryCoreFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryLogRepository inventoryLogRepository;

    @Autowired
    private InventoryOperationRepository inventoryOperationRepository;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryDomainService inventoryDomainService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final Long PRODUCT_1001 = 1001L;
    private static final Long PRODUCT_1002 = 1002L;
    private static final Long PRODUCT_9999 = 9999L;
    private static final int STOCK_50 = 50;
    private static final int STOCK_100 = 100;

    @BeforeEach
    void setUp() {
        // 清理 Redis 幂等 key
        Set<String> idemKeys = stringRedisTemplate.keys(InventoryIdempotencyExecutor.IDEM_PREFIX + "*");
        if (idemKeys != null) stringRedisTemplate.delete(idemKeys);

        // 清理 Redis 库存 key
        Set<String> stockKeys = stringRedisTemplate.keys("inventory:stock:*");
        if (stockKeys != null) stringRedisTemplate.delete(stockKeys);

        // 清理 DB
        inventoryOperationRepository.deleteAll();
        inventoryLogRepository.deleteAll();
        inventoryRepository.deleteAll();
    }

    private void initRedisAvail(Long productCode, int quantity) {
        stringRedisTemplate.opsForValue().set(
                RedisKeys.availableStockKey(productCode), String.valueOf(quantity));
    }

    private Long getRedisAvail(Long productCode) {
        String val = stringRedisTemplate.opsForValue().get(RedisKeys.availableStockKey(productCode));
        return val == null ? null : Long.parseLong(val);
    }

    private Long getRedisLocked(Long productCode) {
        String val = stringRedisTemplate.opsForValue().get(RedisKeys.lockedStockKey(productCode));
        return val == null ? null : Long.parseLong(val);
    }

    // ======================================================================
    //  Admin 接口测试
    // ======================================================================
    @Nested
    @DisplayName("Admin 操作")
    class AdminTests {

        @Test
        @DisplayName("CREATE → DB + Redis 均写入")
        void createStock_success() throws Exception {
            mockMvc.perform(post("/admin/inventories/create")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productCode\": 2001, \"quantity\": 100}"))
                    .andExpect(status().isOk());

            Inventory inv = inventoryRepository.findInventoryByProductCode(2001L).get();
            assertThat(inv.getOnHandStock()).isEqualTo(100);
            assertThat(inv.getSoldStock()).isEqualTo(0);

            assertThat(getRedisAvail(2001L)).isEqualTo(100L);
        }

        @Test
        @DisplayName("CREATE 重复 → 异常回滚，Redis 和 DB 一致")
        void createStock_duplicate() {
            inventoryService.createStock(2002L, 50);
            assertThatThrownBy(() -> inventoryService.createStock(2002L, 50))
                    .isInstanceOf(IllegalArgumentException.class);
            // DB 只有一条
            assertThat(inventoryRepository.findInventoryByProductCode(2002L)).isPresent();
            // Redis avail = 50
            assertThat(getRedisAvail(2002L)).isEqualTo(50L);
        }

        @Test
        @DisplayName("ADD → DB onHandStock + Redis avail 同时增加")
        void addStock_success() throws Exception {
            inventoryRepository.save(new Inventory(PRODUCT_1001, STOCK_50));
            initRedisAvail(PRODUCT_1001, STOCK_50);

            mockMvc.perform(post("/admin/inventories/add")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productCode\": 1001, \"quantity\": 10}"))
                    .andExpect(status().isOk());

            Inventory inv = inventoryRepository.findInventoryByProductCode(PRODUCT_1001).get();
            assertThat(inv.getOnHandStock()).isEqualTo(60);
            assertThat(getRedisAvail(PRODUCT_1001)).isEqualTo(60L);
        }

        @Test
        @DisplayName("DEDUCT → DB onHandStock + Redis avail 同时减少")
        void deductStock_success() throws Exception {
            inventoryRepository.save(new Inventory(PRODUCT_1001, STOCK_50));
            initRedisAvail(PRODUCT_1001, STOCK_50);

            mockMvc.perform(post("/admin/inventories/deduct")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productCode\": 1001, \"quantity\": 5}"))
                    .andExpect(status().isOk());

            Inventory inv = inventoryRepository.findInventoryByProductCode(PRODUCT_1001).get();
            assertThat(inv.getOnHandStock()).isEqualTo(45);
            assertThat(getRedisAvail(PRODUCT_1001)).isEqualTo(45L);
        }

        @Test
        @DisplayName("DEDUCT 超量 → 回滚，Redis 不变")
        void deductStock_exceed_rollback() {
            inventoryRepository.save(new Inventory(PRODUCT_1001, STOCK_50));
            initRedisAvail(PRODUCT_1001, STOCK_50);

            assertThatThrownBy(() -> inventoryService.deductStockDirectly(PRODUCT_1001, 100))
                    .isInstanceOf(IllegalArgumentException.class);

            Inventory inv = inventoryRepository.findInventoryByProductCode(PRODUCT_1001).get();
            assertThat(inv.getOnHandStock()).isEqualTo(50);
            assertThat(getRedisAvail(PRODUCT_1001)).isEqualTo(50L);  // 事务回滚，Redis不变
        }

        @Test
        @DisplayName("不存在的商品 DEDUCT → 回滚，Redis 不变")
        void deductStock_nonExistent() {
            assertThatThrownBy(() -> inventoryService.deductStockDirectly(PRODUCT_9999, 10))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(inventoryLogRepository.count()).isZero();
            // Redis key 不应该被创建
            assertThat(getRedisAvail(PRODUCT_9999)).isNull();
        }
    }

    // ======================================================================
    //  LOCK / CONFIRM / UNLOCK 核心链路（通过 Service 直接调用）
    // ======================================================================
    @Nested
    @DisplayName("Redis 库存操作（LOCK / CONFIRM / UNLOCK）")
    class RedisInventoryOpsTests {

        @BeforeEach
        void initDbAndRedis() {
            inventoryRepository.save(new Inventory(PRODUCT_1001, STOCK_100));
            inventoryRepository.save(new Inventory(PRODUCT_1002, STOCK_100));
            initRedisAvail(PRODUCT_1001, STOCK_100);
            initRedisAvail(PRODUCT_1002, STOCK_100);
        }

        @Test
        @DisplayName("LOCK → Redis avail--, locked++; DB onHandStock 不变")
        void lock_decreasesAvail_increasesLocked() {
            inventoryDomainService.batchLockStock(1L, List.of(new StockRequest(PRODUCT_1001, 10)));

            assertThat(getRedisAvail(PRODUCT_1001)).isEqualTo(90L);
            assertThat(getRedisLocked(PRODUCT_1001)).isEqualTo(10L);
            // DB 不变 (LOCK only touches Redis)
            Inventory inv = inventoryRepository.findInventoryByProductCode(PRODUCT_1001).get();
            assertThat(inv.getOnHandStock()).isEqualTo(100);
            assertThat(inv.getSoldStock()).isZero();
        }

        @Test
        @DisplayName("LOCK 库存不足 → 抛异常，Redis 不变")
        void lock_insufficientStock() {
            assertThatThrownBy(() -> inventoryDomainService.batchLockStock(
                    1L, List.of(new StockRequest(PRODUCT_1001, 200))))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(getRedisAvail(PRODUCT_1001)).isEqualTo(100L);
            assertThat(getRedisLocked(PRODUCT_1001)).isNull();  // 未创建或为 null
        }

        @Test
        @DisplayName("CONFIRM → DB onHandStock--, soldStock++; Redis locked--")
        void confirm_decreasesOnHandAndLocked() {
            // 先 lock (Redis 操作)
            inventoryDomainService.batchLockStock(1L, List.of(new StockRequest(PRODUCT_1001, 20)));

            inventoryDomainService.batchConfirmSale(1L, List.of(new StockRequest(PRODUCT_1001, 15)));

            // DB: onHandStock 减少(100-15=85), sold 增加(0+15=15)
            Inventory inv = inventoryRepository.findInventoryByProductCode(PRODUCT_1001).get();
            assertThat(inv.getOnHandStock()).isEqualTo(85);
            assertThat(inv.getSoldStock()).isEqualTo(15);

            // Redis: avail 不变（LOCK 时已扣, CONFIRM 不操作avail）, locked 减少(20-15=5)
            assertThat(getRedisAvail(PRODUCT_1001)).isEqualTo(80L);
            assertThat(getRedisLocked(PRODUCT_1001)).isEqualTo(5L);
        }

        @Test
        @DisplayName("CONFIRM locked 不足 → 抛异常，DB 回滚，Redis 不变")
        void confirm_insufficientLocked() {
            inventoryDomainService.batchLockStock(1L, List.of(new StockRequest(PRODUCT_1001, 5)));

            assertThatThrownBy(() -> inventoryDomainService.batchConfirmSale(
                    1L, List.of(new StockRequest(PRODUCT_1001, 10))))
                    .isInstanceOf(IllegalArgumentException.class);

            // DB 回滚 (onHandStock 恢复为 100, soldStock 恢复为 0)
            Inventory inv = inventoryRepository.findInventoryByProductCode(PRODUCT_1001).get();
            assertThat(inv.getOnHandStock()).isEqualTo(100);
            assertThat(inv.getSoldStock()).isEqualTo(0);

            // Redis 不变 (avail=95, locked=5 from the successful LOCK)
            assertThat(getRedisAvail(PRODUCT_1001)).isEqualTo(95L);
            assertThat(getRedisLocked(PRODUCT_1001)).isEqualTo(5L);
        }

        @Test
        @DisplayName("UNLOCK → Redis avail++, locked--; DB 不变")
        void unlock_returnsStock() {
            inventoryDomainService.batchLockStock(1L, List.of(new StockRequest(PRODUCT_1001, 30)));

            inventoryDomainService.batchUnlockStock(1L, List.of(new StockRequest(PRODUCT_1001, 10)));

            assertThat(getRedisAvail(PRODUCT_1001)).isEqualTo(80L);
            assertThat(getRedisLocked(PRODUCT_1001)).isEqualTo(20L);
            // DB 不变 (UNLOCK only touches Redis)
            Inventory inv = inventoryRepository.findInventoryByProductCode(PRODUCT_1001).get();
            assertThat(inv.getOnHandStock()).isEqualTo(100);
            assertThat(inv.getSoldStock()).isZero();
        }

        @Test
        @DisplayName("LOCK+CONFIRM+UNLOCK 完整链路 → 恒等式 onHandStock = Redis avail + Redis locked")
        void fullLifecycle_equationHolds() {
            // LOCK 30:  Redis avail=70, locked=30; DB onHandStock=100, sold=0
            inventoryDomainService.batchLockStock(1L, List.of(new StockRequest(PRODUCT_1001, 30)));
            // CONFIRM 20: DB onHandStock=80, sold=20; Redis locked=10 (avail=70 unchanged by CONFIRM)
            inventoryDomainService.batchConfirmSale(1L, List.of(new StockRequest(PRODUCT_1001, 20)));
            // UNLOCK 10: Redis avail=80, locked=0; DB unchanged
            inventoryDomainService.batchUnlockStock(1L, List.of(new StockRequest(PRODUCT_1001, 10)));

            // 恒等式: onHandStock = Redis avail + Redis locked
            Inventory inv = inventoryRepository.findInventoryByProductCode(PRODUCT_1001).get();
            long redisAvail = getRedisAvail(PRODUCT_1001);
            long redisLocked = getRedisLocked(PRODUCT_1001);

            assertThat(inv.getOnHandStock())
                    .as("onHandStock(%d) should equal Redis avail(%d) + Redis locked(%d)",
                            inv.getOnHandStock(), redisAvail, redisLocked)
                    .isEqualTo((int) (redisAvail + redisLocked));

            // soldStock is cumulative audit: confirmSale(20) → sold=20
            assertThat(inv.getSoldStock()).isEqualTo(20);
        }
    }

    // ======================================================================
    //  HTTP API 全链路（含幂等）
    // ======================================================================
    @Nested
    @DisplayName("HTTP API 全链路")
    class HttpApiTests {

        @BeforeEach
        void initDbAndRedis() {
            inventoryRepository.save(new Inventory(PRODUCT_1001, STOCK_50));
            inventoryRepository.save(new Inventory(PRODUCT_1002, STOCK_100));
            initRedisAvail(PRODUCT_1001, STOCK_50);
            initRedisAvail(PRODUCT_1002, STOCK_100);
        }

        @Test
        @DisplayName("POST /inventories/batch/lock → 幂等控制+Redis+日志")
        void batchLock_viaApi() throws Exception {
            InventoryBatchRequest req = new InventoryBatchRequest(10L,
                    List.of(new StockRequest(PRODUCT_1001, 5), new StockRequest(PRODUCT_1002, 10)));

            mockMvc.perform(post("/inventories/batch/lock")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk());

            assertThat(getRedisAvail(PRODUCT_1001)).isEqualTo(45L);
            assertThat(getRedisLocked(PRODUCT_1001)).isEqualTo(5L);
            assertThat(getRedisAvail(PRODUCT_1002)).isEqualTo(90L);
            assertThat(getRedisLocked(PRODUCT_1002)).isEqualTo(10L);

            // 幂等记录存在
            assertThat(inventoryOperationRepository
                    .findByOrderIdAndOperationType(10L, OperationType.LOCK)).isPresent();

            // 日志存在
            List<InventoryLog> logs = inventoryLogRepository.findAll();
            assertThat(logs).hasSize(2);
        }

        @Test
        @DisplayName("POST /inventories/batch/lock 幂等重试 → 只执行一次")
        void batchLock_idempotent() throws Exception {
            InventoryBatchRequest req = new InventoryBatchRequest(20L,
                    List.of(new StockRequest(PRODUCT_1001, 10)));

            // 第一次
            mockMvc.perform(post("/inventories/batch/lock")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)));

            // 第二次（幂等）
            mockMvc.perform(post("/inventories/batch/lock")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk());

            assertThat(getRedisAvail(PRODUCT_1001)).isEqualTo(40L);
            assertThat(getRedisLocked(PRODUCT_1001)).isEqualTo(10L);
        }

        @Test
        @DisplayName("POST /inventories/batch/check → 查 Redis available")
        void batchCheck_viaApi() throws Exception {
            // avail=50, check 30+20 → 够
            InventoryBatchRequest req = new InventoryBatchRequest(30L,
                    List.of(new StockRequest(PRODUCT_1001, 30), new StockRequest(PRODUCT_1002, 20)));

            String resp = mockMvc.perform(post("/inventories/batch/check")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            SimpleResponse<InventoryBatchCheckResult> result = objectMapper.readValue(
                    resp, new TypeReference<SimpleResponse<InventoryBatchCheckResult>>() {});
            assertThat(result.getData().isAllValid()).isTrue();
        }

        @Test
        @DisplayName("POST /inventories/batch/check → 库存不足时返回失败")
        void batchCheck_insufficient() throws Exception {
            // avail=50, check 60 → 不够
            InventoryBatchRequest req = new InventoryBatchRequest(30L,
                    List.of(new StockRequest(PRODUCT_1001, 60)));

            String resp = mockMvc.perform(post("/inventories/batch/check")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            SimpleResponse<InventoryBatchCheckResult> result = objectMapper.readValue(
                    resp, new TypeReference<SimpleResponse<InventoryBatchCheckResult>>() {});
            assertThat(result.getData().isAllValid()).isFalse();
            assertThat(result.getData().getFailedProductCodes()).contains(PRODUCT_1001);
        }
    }

    // ======================================================================
    //  幂等框架测试
    // ======================================================================
    @Nested
    @DisplayName("幂等控制")
    class IdempotencyTests {

        @BeforeEach
        void init() {
            inventoryRepository.save(new Inventory(PRODUCT_1001, STOCK_100));
            initRedisAvail(PRODUCT_1001, STOCK_100);
        }

        @Test
        @DisplayName("相同 orderId 重复 LOCK → 幂等返回，库存只扣一次")
        void sameOrderId_lockOnce() {
            inventoryService.batchLockStockWithIdempotency(
                    new InventoryBatchRequest(99L, List.of(new StockRequest(PRODUCT_1001, 10))));

            // 第二次调用幂等执行器，应幂等返回
            inventoryService.batchLockStockWithIdempotency(
                    new InventoryBatchRequest(99L, List.of(new StockRequest(PRODUCT_1001, 10))));

            // Redis avail 只减少一次 (100-10=90)
            assertThat(getRedisAvail(PRODUCT_1001)).isEqualTo(90L);
            assertThat(getRedisLocked(PRODUCT_1001)).isEqualTo(10L);
        }

        @Test
        @DisplayName("不同 orderId LOCK 同一商品 → 仅第一个成功（Lua atomicity）")
        void differentOrderId_lockConflict() {
            inventoryService.batchLockStockWithIdempotency(
                    new InventoryBatchRequest(88L, List.of(new StockRequest(PRODUCT_1001, 10))));

            // 第二个不同 orderId 锁定同商品，Redis avail 应只剩 90，锁定 10 没问题
            inventoryService.batchLockStockWithIdempotency(
                    new InventoryBatchRequest(89L, List.of(new StockRequest(PRODUCT_1001, 5))));
            assertThat(getRedisAvail(PRODUCT_1001)).isEqualTo(85L);
            assertThat(getRedisLocked(PRODUCT_1001)).isEqualTo(15L);
        }
    }

    // ======================================================================
    //  事务回滚测试
    // ======================================================================
    @Nested
    @DisplayName("事务回滚")
    class TransactionTests {

        @BeforeEach
        void init() {
            inventoryRepository.save(new Inventory(PRODUCT_1001, STOCK_50));
            initRedisAvail(PRODUCT_1001, STOCK_50);
        }

        @Test
        @DisplayName("CONFIRM 失败 → DB 回滚，Redis 不变")
        void confirmFailure_rollback() {
            inventoryDomainService.batchLockStock(1L, List.of(new StockRequest(PRODUCT_1001, 5)));

            long lockedBefore = getRedisLocked(PRODUCT_1001);

            // 尝试 CONFIRM 超过 locked 的数量
            assertThatThrownBy(() -> inventoryDomainService.batchConfirmSale(
                    1L, List.of(new StockRequest(PRODUCT_1001, 10))))
                    .isInstanceOf(IllegalArgumentException.class);

            // DB 回滚
            Inventory inv = inventoryRepository.findInventoryByProductCode(PRODUCT_1001).get();
            assertThat(inv.getOnHandStock()).isEqualTo(50);
            assertThat(inv.getSoldStock()).isEqualTo(0);

            // Redis 不变
            assertThat(getRedisLocked(PRODUCT_1001)).isEqualTo(lockedBefore);
        }
    }

    // ======================================================================
    //  InventoryLog 审计测试
    // ======================================================================
    @Nested
    @DisplayName("InventoryLog 审计日志")
    class InventoryLogTests {

        @BeforeEach
        void init() {
            inventoryRepository.save(new Inventory(PRODUCT_1001, STOCK_50));
            initRedisAvail(PRODUCT_1001, STOCK_50);
        }

        @Test
        @DisplayName("所有 6 种 OperationType 都有日志")
        void allOperationTypes_logged() {
            inventoryService.createStock(3001L, 100);
            inventoryService.addStock(PRODUCT_1001, 10);
            inventoryService.deductStockDirectly(PRODUCT_1001, 5);
            inventoryDomainService.batchLockStock(1L, List.of(new StockRequest(PRODUCT_1001, 5)));
            inventoryDomainService.batchConfirmSale(1L, List.of(new StockRequest(PRODUCT_1001, 3)));
            inventoryDomainService.batchUnlockStock(1L, List.of(new StockRequest(PRODUCT_1001, 2)));

            List<InventoryLog> logs = inventoryLogRepository.findAll();
            assertThat(logs).extracting(InventoryLog::getOperationType)
                    .containsExactlyInAnyOrder(
                            OperationType.MANUAL_CREATE,
                            OperationType.MANUAL_ADD,
                            OperationType.MANUAL_DEDUCT,
                            OperationType.LOCK,
                            OperationType.CONFIRM,
                            OperationType.UNLOCK);
        }
    }
}