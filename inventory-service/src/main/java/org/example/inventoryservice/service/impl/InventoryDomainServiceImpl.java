package org.example.inventoryservice.service.impl;

import org.common.inventory.dto.StockRequest;
import org.example.inventoryservice.domain.InventoryLog;
import org.example.inventoryservice.domain.OperationType;
import org.example.inventoryservice.repository.InventoryLogRepository;
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

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void batchLockStock(Long orderId, List<StockRequest> stockRequestList){
        // lock all the stock
        List<Long> productCodes = stockRequestList.stream()
                .map(StockRequest::getProductCode)
                .toList();

        List<Inventory> inventoryList = inventoryRepository.findInventoriesByProductCodeIn(productCodes);

        Map<Long,Inventory> inventoryMap = inventoryList.stream().collect(Collectors.toMap(Inventory::getProductCode,inv->inv));

        List<InventoryLog> logList = new ArrayList<>();

        for(StockRequest stockRequest : stockRequestList){
            Long productCode = stockRequest.getProductCode();
            Integer quantity = stockRequest.getQuantity();

            Inventory inventory = inventoryMap.get(productCode);
            if(inventory == null){
                throw new IllegalArgumentException("Product not found" + productCode);
            }
            inventory.lock(quantity);

            InventoryLog inventoryLog = new InventoryLog(productCode, quantity, orderId, OperationType.LOCK);
            logList.add(inventoryLog);
        }

        inventoryLogRepository.saveAll(logList);
    }
    @Override
    @Transactional
    public void batchConfirmSale(Long orderId, List<StockRequest> stockRequestList){

        List<Long> productCodes = stockRequestList.stream()
                .map(StockRequest::getProductCode)
                .toList();
        List<Inventory> inventoryList = inventoryRepository.findInventoriesByProductCodeIn(productCodes);

        Map<Long,Inventory> inventoryMap = inventoryList.stream().collect(Collectors.toMap(Inventory::getProductCode, inv->inv));

        List<InventoryLog> logList = new ArrayList<>();

        for(StockRequest stockRequest : stockRequestList){
            Long productCode = stockRequest.getProductCode();
            Integer quantity = stockRequest.getQuantity();

            Inventory inventory = inventoryMap.get(productCode);
            if(inventory == null) throw new IllegalArgumentException("Product not found: " + productCode);
            inventory.confirmSale(quantity);

            InventoryLog inventoryLog = new InventoryLog(productCode, quantity, orderId, OperationType.CONFIRM);
            logList.add(inventoryLog);
        }

        inventoryLogRepository.saveAll(logList);
    }
    @Override
    @Transactional
    public void batchUnlockStock(Long orderId, List<StockRequest> stockRequestList){
        List<Long> productCodes = stockRequestList.stream()
                .map(StockRequest::getProductCode)
                .toList();
        List<Inventory> inventoryList = inventoryRepository.findInventoriesByProductCodeIn(productCodes);

        Map<Long,Inventory> inventoryMap = inventoryList.stream().collect(Collectors.toMap(Inventory::getProductCode,inv->inv));

        List<InventoryLog> logList = new ArrayList<>();

        for(StockRequest stockRequest : stockRequestList){
            Long productCode = stockRequest.getProductCode();
            Integer quantity = stockRequest.getQuantity();

            Inventory inventory = inventoryMap.get(productCode);
            if(inventory == null) throw new IllegalArgumentException("Product not found: " + productCode);
            inventory.unlock(quantity);

            InventoryLog inventoryLog = new InventoryLog(productCode, quantity, orderId, OperationType.UNLOCK);
            logList.add(inventoryLog);
        }

        inventoryLogRepository.saveAll(logList);
    }
}
