package org.example.inventoryservice.service.impl;

import org.common.inventory.dto.StockRequest;
import org.example.inventoryservice.config.RedisKeys;
import org.example.inventoryservice.domain.InventoryLog;
import org.example.inventoryservice.domain.OperationType;
import org.example.inventoryservice.repository.InventoryLogRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.example.inventoryservice.domain.Inventory;
import org.example.inventoryservice.repository.InventoryRepository;
import org.example.inventoryservice.service.InventoryDomainService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InventoryDomainServiceImpl implements InventoryDomainService {

    private final InventoryRepository inventoryRepository;
    private final InventoryLogRepository inventoryLogRepository;
    private final DefaultRedisScript<Long> lockScript;
    private final DefaultRedisScript<Long> unlockScript;
    private final DefaultRedisScript<Long> confirmScript;
    private final StringRedisTemplate  stringRedisTemplate;
    private final EntityManager entityManager;


    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void batchLockStock(Long orderId, List<StockRequest> stockRequestList){
        // lock all the stock
        List<Long> productCodes = stockRequestList.stream()
                .map(StockRequest::getProductCode)
                .toList();

        List<Inventory> inventoryList = inventoryRepository.findInventoriesByProductCodeIn(productCodes);

        Map<Long,Inventory> inventoryMap = inventoryList.stream().collect(Collectors.toMap(Inventory::getProductCode,inv->inv));

        List<StockRequest> lockedSuccessfully = new ArrayList<>();
        List<InventoryLog> logList = new ArrayList<>();

        try {
            for (StockRequest stockRequest : stockRequestList) {
                Long productCode = stockRequest.getProductCode();
                Integer quantity = stockRequest.getQuantity();

                Inventory inventory = inventoryMap.get(productCode);
                if (inventory == null) {
                    throw new IllegalArgumentException("Product not found" + productCode);
                }

                // redis lua avail-- locked++
                Long result = stringRedisTemplate.execute(lockScript,
                        List.of(RedisKeys.availableStockKey(productCode), RedisKeys.lockedStockKey(productCode))
                        , String.valueOf(quantity));

                if (result == 0) {
                    throw new IllegalArgumentException("Insufficient stock for product: " + productCode);
                }

                lockedSuccessfully.add(stockRequest);
                InventoryLog inventoryLog = new InventoryLog(productCode, quantity, orderId, OperationType.LOCK);
                logList.add(inventoryLog);
            }

            inventoryLogRepository.saveAll(logList);

        } catch (Exception e) {
            // 回滚已成功的 Redis 操作：avail++, locked--
            for (StockRequest req : lockedSuccessfully) {
                try {
                    stringRedisTemplate.opsForValue().increment(
                            RedisKeys.availableStockKey(req.getProductCode()), req.getQuantity());
                    stringRedisTemplate.opsForValue().decrement(
                            RedisKeys.lockedStockKey(req.getProductCode()), req.getQuantity());
                } catch (Exception ignored) {
                    // 回滚失败不应掩盖原始异常
                }
            }
            // DB 由 @Transactional 自动回滚
            throw e;
        }
    }
    @Override
    @Transactional
    public void batchConfirmSale(Long orderId, List<StockRequest> stockRequestList){

        List<Long> productCodes = stockRequestList.stream()
                .map(StockRequest::getProductCode)
                .toList();
        List<Inventory> inventoryList = inventoryRepository.findInventoriesByProductCodeIn(productCodes);

        Map<Long,Inventory> inventoryMap = inventoryList.stream().collect(Collectors.toMap(Inventory::getProductCode, inv->inv));

        List<StockRequest> redisSuccessfully = new ArrayList<>();
        List<InventoryLog> logList = new ArrayList<>();

        try {
            for (StockRequest stockRequest : stockRequestList) {
                Long productCode = stockRequest.getProductCode();
                Integer quantity = stockRequest.getQuantity();

                Inventory inventory = inventoryMap.get(productCode);
                if (inventory == null) throw new IllegalArgumentException("Product not found: " + productCode);
                // db onhand-- sold++
                inventory.confirmSale(quantity);

                InventoryLog inventoryLog = new InventoryLog(productCode, quantity, orderId, OperationType.CONFIRM);
                logList.add(inventoryLog);
            }

            inventoryLogRepository.saveAll(logList);

            // Force flush to DB now. If OptimisticLockFailure occurs, it will happen HERE,
            // still inside the try-catch block. Redis has NOT been touched yet, so we can
            // safely delete the PROCESSING idempotency record and retry.
            entityManager.flush();

            // DB flush succeeded (no OptimisticLockFailure, no constraint violation),
            // execute Redis operations.
            for (StockRequest stockRequest : stockRequestList) {
                Long productCode = stockRequest.getProductCode();
                Integer quantity = stockRequest.getQuantity();

                Long result = stringRedisTemplate.execute(confirmScript,
                        List.of(RedisKeys.lockedStockKey(productCode))
                        , String.valueOf(quantity));

                if (result == 0) {
                    // Redis locked insufficient — this should not happen if validateAndLockStock worked
                    // But if it does (e.g. parallel unlock consumed the stock), we can't rollback DB.
                    // Throw to trigger retry or dead letter.
                    throw new IllegalArgumentException(
                            "Confirm failed: insufficient locked stock for product: " + productCode +
                            " after DB commit. OrderId: " + orderId);
                }

                redisSuccessfully.add(stockRequest);
            }

        } catch (jakarta.persistence.OptimisticLockException e) {
            // entityManager.flush() throws Jakarta Persistence OptimisticLockException,
            // NOT Spring's wrapper. Convert to Spring's OptimisticLockingFailureException
            // so that InventoryIdempotencyExecutor can recognize and retry it.
            throw new org.springframework.dao.OptimisticLockingFailureException("Optimistic lock conflict at flush time", e);
        } catch (Exception e) {
            // If some items already had Redis decremented, roll them back.
            for (StockRequest req : redisSuccessfully) {
                try {
                    stringRedisTemplate.opsForValue().increment(
                            RedisKeys.lockedStockKey(req.getProductCode()), req.getQuantity());
                } catch (Exception ignored) {
                }
            }
            // DB 由 @Transactional 自动回滚
            throw e;
        }
    }

    @Override
    @Transactional
    public void batchUnlockStock(Long orderId, List<StockRequest> stockRequestList){
        List<Long> productCodes = stockRequestList.stream()
                .map(StockRequest::getProductCode)
                .toList();
        List<Inventory> inventoryList = inventoryRepository.findInventoriesByProductCodeIn(productCodes);

        Map<Long,Inventory> inventoryMap = inventoryList.stream().collect(Collectors.toMap(Inventory::getProductCode,inv->inv));

        List<StockRequest> unlockedSuccessfully = new ArrayList<>();
        List<InventoryLog> logList = new ArrayList<>();

        try {
            for (StockRequest stockRequest : stockRequestList) {
                Long productCode = stockRequest.getProductCode();
                Integer quantity = stockRequest.getQuantity();

                Inventory inventory = inventoryMap.get(productCode);
                if (inventory == null) throw new IllegalArgumentException("Product not found: " + productCode);

                // redis lua avail++ locked--
                Long result = stringRedisTemplate.execute(unlockScript,
                        List.of(RedisKeys.availableStockKey(productCode), RedisKeys.lockedStockKey(productCode))
                        , String.valueOf(quantity));

                if (result == 0) {
                    throw new IllegalArgumentException("Unlock failed: insufficient locked stock for product:  " + productCode);
                }

                unlockedSuccessfully.add(stockRequest);
                InventoryLog inventoryLog = new InventoryLog(productCode, quantity, orderId, OperationType.UNLOCK);
                logList.add(inventoryLog);
            }

            inventoryLogRepository.saveAll(logList);

        } catch (Exception e) {
            // 回滚已成功的 Redis 操作：avail--, locked++
            for (StockRequest req : unlockedSuccessfully) {
                try {
                    stringRedisTemplate.opsForValue().decrement(
                            RedisKeys.availableStockKey(req.getProductCode()), req.getQuantity());
                    stringRedisTemplate.opsForValue().increment(
                            RedisKeys.lockedStockKey(req.getProductCode()), req.getQuantity());
                } catch (Exception ignored) {
                    // 回滚失败不应掩盖原始异常
                }
            }
            // DB 由 @Transactional 自动回滚
            throw e;
        }
    }
}