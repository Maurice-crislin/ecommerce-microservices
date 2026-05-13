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
                        , quantity);

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

        List<StockRequest> confirmedSuccessfully = new ArrayList<>();
        List<InventoryLog> logList = new ArrayList<>();

        try {
            for (StockRequest stockRequest : stockRequestList) {
                Long productCode = stockRequest.getProductCode();
                Integer quantity = stockRequest.getQuantity();

                Inventory inventory = inventoryMap.get(productCode);
                if (inventory == null) throw new IllegalArgumentException("Product not found: " + productCode);
                // db onhand-- sold++
                inventory.confirmSale(quantity);

                // redis lua locked--
                Long result = stringRedisTemplate.execute(confirmScript,
                        List.of(RedisKeys.lockedStockKey(productCode))
                        , quantity);

                if (result == 0) {
                    throw new IllegalArgumentException("Confirm failed: insufficient locked stock for product: " + productCode);
                }

                confirmedSuccessfully.add(stockRequest);
                InventoryLog inventoryLog = new InventoryLog(productCode, quantity, orderId, OperationType.CONFIRM);
                logList.add(inventoryLog);
            }

            inventoryLogRepository.saveAll(logList);

        } catch (Exception e) {
            // 回滚已成功的 Redis 操作：locked++
            for (StockRequest req : confirmedSuccessfully) {
                try {
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
                        , quantity);

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
