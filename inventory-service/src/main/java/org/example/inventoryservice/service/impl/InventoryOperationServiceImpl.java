package org.example.inventoryservice.service.impl;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.example.inventoryservice.domain.InventoryOperation;
import org.example.inventoryservice.domain.OperationType;
import org.example.inventoryservice.repository.InventoryOperationRepository;
import org.example.inventoryservice.service.InventoryOperationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;

@RequiredArgsConstructor
@Service
public class InventoryOperationServiceImpl implements InventoryOperationService {
    private final InventoryOperationRepository inventoryOperationRepository;


    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InventoryOperation getOrStartOperation(Long orderId, OperationType operationType){
        // create record
        InventoryOperation inventoryOperation = InventoryOperation.start(orderId, operationType);
        try {
            // and save
            inventoryOperationRepository.saveAndFlush(inventoryOperation); // quickly insert
        } catch (DataIntegrityViolationException e) {
            // cannnot save means already exists
            throw e;
        }
        return inventoryOperation;
    }

    public InventoryOperation getOperationByOrderIdAndOperationType(Long orderId, OperationType operationType){

        return inventoryOperationRepository
                .findByOrderIdAndOperationType(orderId,operationType)
                .orElseThrow(()-> new IllegalArgumentException("Operation not found"));
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSuccess(Long orderId, OperationType operationType){
        InventoryOperation inventoryOperation = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderId,operationType)
                .orElseThrow(()-> new IllegalArgumentException("Operation not found"));
        inventoryOperation.markSuccess();
        // must save now
        inventoryOperationRepository.saveAndFlush(inventoryOperation);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long orderId, OperationType operationType){
        InventoryOperation inventoryOperation = inventoryOperationRepository
                .findByOrderIdAndOperationType(orderId,operationType)
                .orElseThrow(()-> new IllegalArgumentException("Operation not found"));
        inventoryOperation.markFailed();
        // must save now
        inventoryOperationRepository.saveAndFlush(inventoryOperation);
    }
}
