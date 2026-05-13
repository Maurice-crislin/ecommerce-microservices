package org.example.inventoryservice.service.impl;

import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.dto.StockRequest;
import org.example.inventoryservice.domain.*;
import org.example.inventoryservice.repository.InventoryLogRepository;
import org.example.inventoryservice.service.InventoryIdempotencyExecutor;
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

    @Override
    @Transactional
    // admin
    public void deductStockDirectly(Long productCode, Integer quantity) {
        Inventory inventory = inventoryRepository.findInventoryByProductCode(productCode)
                .orElseThrow(()-> new IllegalArgumentException("Product not found"));

        inventory.deductStock(quantity);

        // no need to save()
        // Spring Data JPA 默认在事务中开启 dirty checking（脏检查）
        // 事务提交时 Hibernate 会自动：
        // 检测 entity 是否变更 → 自动生成 update SQL
        InventoryLog inventoryLog = new InventoryLog(productCode,quantity,null,OperationType.MANUAL_DEDUCT);
        inventoryLogRepository.save(inventoryLog);
    }

    @Override
    @Transactional
    // admin
    public void addStock(Long productCode, Integer quantity) {
        Inventory inventory = inventoryRepository.findInventoryByProductCode(productCode)
                .orElseThrow(()-> new IllegalArgumentException("Product not found"));
        inventory.increaseStock(quantity);

        InventoryLog inventoryLog = new InventoryLog(productCode,quantity,null,OperationType.MANUAL_ADD);
        inventoryLogRepository.save(inventoryLog);
    }
    @Override
    @Transactional
    public void createStock(Long productCode, Integer quantity) {
        if(inventoryRepository.existsByProductCode(productCode)){
            throw new IllegalArgumentException("Product already exists");
        }

        Inventory inventory = new Inventory(productCode, quantity);
        inventoryRepository.save(inventory);

        InventoryLog inventoryLog = new InventoryLog(productCode,quantity,null,OperationType.MANUAL_CREATE);
        inventoryLogRepository.save(inventoryLog);
    }

    @Override
    // just check, no deduct/lock
    public List<Long> batchCheckStock(List<StockRequest> stockRequestList) {


        List<Long> productCodes = stockRequestList.stream()
                .map(StockRequest::getProductCode)
                .toList();

        List<Inventory> inventoryList = inventoryRepository.findInventoriesByProductCodeIn(productCodes);
        Map<Long,Inventory> inventoryMap = inventoryList.stream().collect(Collectors.toMap(Inventory::getProductCode,inv->inv));
        List<Long> failedProductCodes = new ArrayList<>();

        for(StockRequest stockRequest : stockRequestList){
            Inventory inventory = inventoryMap.get(stockRequest.getProductCode());
            if(inventory == null || inventory.getOnHandStock() < stockRequest.getQuantity()){
                failedProductCodes.add(stockRequest.getProductCode());
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
