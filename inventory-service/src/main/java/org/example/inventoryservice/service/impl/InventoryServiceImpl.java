package org.example.inventoryservice.service.impl;

import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.dto.StockRequest;
import org.example.inventoryservice.config.RedisKeys;
import org.example.inventoryservice.domain.*;
import org.example.inventoryservice.repository.InventoryLogRepository;
import org.example.inventoryservice.service.InventoryIdempotencyExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.example.inventoryservice.repository.InventoryRepository;
import org.example.inventoryservice.service.InventoryDomainService;
import org.example.inventoryservice.service.InventoryService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private final InventoryRepository inventoryRepository;
    private final InventoryDomainService inventoryDomainService;
    private final InventoryLogRepository  inventoryLogRepository;
    private final InventoryIdempotencyExecutor inventoryIdempotencyExecutor;
    private final StringRedisTemplate  stringRedisTemplate;

    @Override
    @Transactional
    // admin
    public void deductStockDirectly(Long productCode, Integer quantity) {
        // 1. 先查 DB 校验
        Inventory inventory = inventoryRepository.findInventoryByProductCode(productCode)
                .orElseThrow(()-> new IllegalArgumentException("Product not found"));


        // 2. 先操作 Redis（如果 Redis 失败，DB 不会被执行）
        String availableStock = RedisKeys.availableStockKey(productCode);
        stringRedisTemplate.opsForValue().decrement(availableStock,quantity);

        // 3. Redis 成功后，再操作 DB
        inventory.deductStock(quantity);

        InventoryLog inventoryLog = new InventoryLog(productCode,quantity,null,OperationType.MANUAL_DEDUCT);
        inventoryLogRepository.save(inventoryLog);
        // no need to save()
        // Spring Data JPA 默认在事务中开启 dirty checking（脏检查）
        // 事务提交时 Hibernate 会自动：
        // 检测 entity 是否变更 → 自动生成 update SQL
    }

    @Override
    @Transactional
    // admin
    public void addStock(Long productCode, Integer quantity) {
        // 1. 先查 DB 校验
        Inventory inventory = inventoryRepository.findInventoryByProductCode(productCode)
                .orElseThrow(()-> new IllegalArgumentException("Product not found"));

        // 2.先操作 Redis（如果 Redis 失败，DB 不会被执行）
        String availableStock = RedisKeys.availableStockKey(productCode);
        stringRedisTemplate.opsForValue().increment(availableStock,quantity);

        // 3. Redis 成功后，再操作 DB
        inventory.increaseStock(quantity);
        InventoryLog inventoryLog = new InventoryLog(productCode,quantity,null,OperationType.MANUAL_ADD);
        inventoryLogRepository.save(inventoryLog);
    }
    @Override
    @Transactional
    public void createStock(Long productCode, Integer quantity) {
        // 1. 先查 DB 校验
        if(inventoryRepository.existsByProductCode(productCode)){
            throw new IllegalArgumentException("Product already exists");
        }
        // 2.先操作 Redis（如果 Redis 失败，DB 不会被执行）
        String availableStockKey = RedisKeys.availableStockKey(productCode);
        stringRedisTemplate.opsForValue().set(availableStockKey,String.valueOf(quantity));

        // 3. Redis 成功后，再操作 DB
        Inventory inventory = new Inventory(productCode, quantity);
        inventoryRepository.save(inventory);
        InventoryLog inventoryLog = new InventoryLog(productCode,quantity,null,OperationType.MANUAL_CREATE);
        inventoryLogRepository.save(inventoryLog);
    }

    @Override
    // just check, no deduct/lock
    public List<Long> batchCheckStock(List<StockRequest> stockRequestList) {

        List<Long> failedProductCodes = new ArrayList<>();

        for(StockRequest stockRequest : stockRequestList){
            Long productCode = stockRequest.getProductCode();
            Integer quantity = stockRequest.getQuantity();

            //read from redis
            String key =  RedisKeys.availableStockKey(productCode);
            String availableStr =  stringRedisTemplate.opsForValue().get(key);
            if(availableStr == null){
                // redis没有这个值
                failedProductCodes.add(productCode);
                continue;
            }
            int availableStock = Integer.parseInt(availableStr);
            if(availableStock < quantity){
                // 库存存在但是满足不了
                failedProductCodes.add(productCode);
            }

        }
        return failedProductCodes;
    }



    // api调用 没有retry设置,客户端接到500之后自行决定重试
    @Override
    public void batchLockStockWithIdempotency(InventoryBatchRequest inventoryBatchRequest){
        Long orderId = inventoryBatchRequest.getOrderId();
        inventoryIdempotencyExecutor.executeWithIdempotency(
                inventoryBatchRequest.getOrderId(),
                OperationType.LOCK,
                () ->inventoryDomainService.batchLockStock(orderId,inventoryBatchRequest.getStockRequestList())
        );
    }


    // RabbitMQ调用 + @Retryable
    @Override
    public void batchConfirmSaleWithIdempotency(InventoryBatchRequest inventoryBatchRequest){
        Long orderId = inventoryBatchRequest.getOrderId();
        inventoryIdempotencyExecutor.executeWithIdempotency(
                orderId,
                OperationType.CONFIRM,
                () ->inventoryDomainService.batchConfirmSale(orderId,inventoryBatchRequest.getStockRequestList())
        );
    }
    // RabbitMQ调用 + @Retryable
    @Override
    public void batchUnlockStockWithIdempotency(InventoryBatchRequest inventoryBatchRequest){
        Long orderId = inventoryBatchRequest.getOrderId();
        inventoryIdempotencyExecutor.executeWithIdempotency(
                inventoryBatchRequest.getOrderId(),
                OperationType.UNLOCK,
                () ->inventoryDomainService.batchUnlockStock(orderId,inventoryBatchRequest.getStockRequestList())
        );
    }
}
