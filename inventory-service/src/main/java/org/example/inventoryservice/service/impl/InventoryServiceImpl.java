package org.example.inventoryservice.service.impl;

import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.dto.StockRequest;
import org.example.inventoryservice.exception.OperationProcessingException;
import org.example.inventoryservice.service.InventoryIdempotencyExecutor;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.example.inventoryservice.domain.Inventory;
import org.example.inventoryservice.domain.InventoryOperation;
import org.example.inventoryservice.domain.OperationStatus;
import org.example.inventoryservice.domain.OperationType;
import org.example.inventoryservice.repository.InventoryOperationRepository;
import org.example.inventoryservice.repository.InventoryRepository;
import org.example.inventoryservice.service.InventoryDomainService;
import org.example.inventoryservice.service.InventoryOperationService;
import org.example.inventoryservice.service.InventoryService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private final InventoryRepository inventoryRepository;
    private final InventoryOperationService inventoryOperationService;
    private final InventoryDomainService inventoryDomainService;
    private final InventoryIdempotencyExecutor inventoryIdempotencyExecutor;

    @Override
    @Transactional
    // admin
    public void deductStockDirectly(Long productCode, Integer quantity) {
        Inventory inventory = inventoryRepository.findInventoryByProductCode(productCode)
                .orElseThrow(()-> new IllegalArgumentException("Product not found"));

        inventory.deductStock(quantity);

        // no need to save()
        // because jpa can auto flush when transition was submitted

    }

    @Override
    @Transactional
    // admin
    public void addStock(Long productCode, Integer quantity) {
        Inventory inventory = inventoryRepository.findInventoryByProductCode(productCode)
                .orElseThrow(()-> new IllegalArgumentException("Product not found"));
        inventory.increaseStock(quantity);

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
            if(inventory == null || inventory.getAvailableStock() < stockRequest.getQuantity()){
                failedProductCodes.add(stockRequest.getProductCode());
            }
        }
        return failedProductCodes;
    }



    @Override
    public void batchLockStockWithIdempotency(InventoryBatchRequest inventoryBatchRequest){
        inventoryIdempotencyExecutor.executeWithIdempotency(
                inventoryBatchRequest.getOrderId(),
                OperationType.LOCK,
                () ->inventoryDomainService.batchLockStock(inventoryBatchRequest.getStockRequestList())
        );
    }


    @Override
    public void batchConfirmSaleWithIdempotency(InventoryBatchRequest inventoryBatchRequest){
        inventoryIdempotencyExecutor.executeWithIdempotency(
                inventoryBatchRequest.getOrderId(),
                OperationType.CONFIRM,
                () ->inventoryDomainService.batchConfirmSale(inventoryBatchRequest.getStockRequestList())
        );
    }
    @Override
    public void batchUnlockStockWithIdempotency(InventoryBatchRequest inventoryBatchRequest){
        inventoryIdempotencyExecutor.executeWithIdempotency(
                inventoryBatchRequest.getOrderId(),
                OperationType.UNLOCK,
                () ->inventoryDomainService.batchUnlockStock(inventoryBatchRequest.getStockRequestList())
        );
    }
}
