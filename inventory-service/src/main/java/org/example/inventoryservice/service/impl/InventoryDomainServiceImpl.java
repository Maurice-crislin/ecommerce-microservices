package org.example.inventoryservice.service.impl;

import org.common.inventory.dto.StockRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.example.inventoryservice.domain.Inventory;
import org.example.inventoryservice.repository.InventoryRepository;
import org.example.inventoryservice.service.InventoryDomainService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InventoryDomainServiceImpl implements InventoryDomainService {

    private final InventoryRepository inventoryRepository;
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void batchLockStock(List<StockRequest> stockRequestList){
        // lock all the stock
        List<Long> productCodes = stockRequestList.stream()
                .map(StockRequest::getProductCode)
                .toList();

        List<Inventory> inventoryList = inventoryRepository.findInventoriesByProductCodeIn(productCodes);

        Map<Long,Inventory> inventoryMap = inventoryList.stream().collect(Collectors.toMap(Inventory::getProductCode,inv->inv));
        for(StockRequest stockRequest : stockRequestList){
            Inventory inventory = inventoryMap.get(stockRequest.getProductCode());
            if(inventory == null){
                throw new IllegalArgumentException("Product not found" + stockRequest.getProductCode());
            }
            inventory.lock(stockRequest.getQuantity());
        }
    }
    @Override
    @Transactional
    public void batchConfirmSale(List<StockRequest> stockRequestList){

        List<Long> productCodes = stockRequestList.stream()
                .map(StockRequest::getProductCode)
                .toList();
        List<Inventory> inventoryList = inventoryRepository.findInventoriesByProductCodeIn(productCodes);

        Map<Long,Inventory> inventoryMap = inventoryList.stream().collect(Collectors.toMap(Inventory::getProductCode, inv->inv));

        for(StockRequest stockRequest : stockRequestList){
            Inventory inventory = inventoryMap.get(stockRequest.getProductCode());
            if(inventory == null) throw new IllegalArgumentException("Product not found: " + stockRequest.getProductCode());
            inventory.confirmSale(stockRequest.getQuantity());
        }

    }
    @Override
    @Transactional
    public void batchUnlockStock(List<StockRequest> stockRequestList){
        List<Long> productCodes = stockRequestList.stream()
                .map(StockRequest::getProductCode)
                .toList();
        List<Inventory> inventoryList = inventoryRepository.findInventoriesByProductCodeIn(productCodes);

        Map<Long,Inventory> inventoryMap = inventoryList.stream().collect(Collectors.toMap(Inventory::getProductCode,inv->inv));

        for(StockRequest stockRequest : stockRequestList){
            Inventory inventory = inventoryMap.get(stockRequest.getProductCode());
            if(inventory == null) throw new IllegalArgumentException("Product not found: " + stockRequest.getProductCode());
            inventory.unlock(stockRequest.getQuantity());
        }
    }
}
