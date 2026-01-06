package org.example.inventoryservice.service;

import lombok.RequiredArgsConstructor;
import org.example.inventoryservice.domain.InventoryOperation;
import org.example.inventoryservice.domain.OperationStatus;
import org.example.inventoryservice.domain.OperationType;
import org.example.inventoryservice.exception.OperationProcessingException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryIdempotencyExecutor {
    private final InventoryOperationService inventoryOperationService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executeWithIdempotency(Long orderId,OperationType operationType, Runnable batchLogic) throws DataIntegrityViolationException, OperationProcessingException {
        try {
            inventoryOperationService.getOrStartOperation(orderId, operationType);
        } catch (DataIntegrityViolationException e) {

            InventoryOperation inventoryOperation = inventoryOperationService.getOperationByOrderIdAndOperationType(orderId,operationType);

            if(inventoryOperation.getOperationStatus() == OperationStatus.FAILED){
                throw new IllegalStateException("Previous operation failed for order " + orderId);
            }

            if(inventoryOperation.getOperationStatus() == OperationStatus.SUCCESS){
                return;
            }
            if (inventoryOperation.getOperationStatus() == OperationStatus.PROCESSING){
                throw new OperationProcessingException("Inventory lock is still processing for order " + orderId);
            }
        }


        try {
            // inventoryDomainService.batchLockStock(inventoryBatchEvent.getStockRequestList());
            batchLogic.run();
            inventoryOperationService.markSuccess(orderId,operationType);
        } catch (IllegalArgumentException e) {
            inventoryOperationService.markFailed(orderId,operationType);
            throw e; // batchLockStock may throw out 'out of stock'
        }
    }
}
