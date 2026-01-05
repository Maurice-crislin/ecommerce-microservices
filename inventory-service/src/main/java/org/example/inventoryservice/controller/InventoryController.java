package org.example.inventoryservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.common.inventory.dto.InventoryBatchRequest;
import org.common.product.dto.InventoryBatchCheckResult;
import org.example.inventoryservice.dto.SimpleResponse;
import org.example.inventoryservice.service.InventoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/inventories")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;
    @PostMapping("/batch/lock")
    public ResponseEntity<SimpleResponse<Object>>  batchLockStock (@RequestBody @Valid InventoryBatchRequest request){

        inventoryService.batchLockStockWithIdempotency(request);
        return ResponseEntity.ok(new SimpleResponse<>(true,"Stock locked successfully"));
    }

    @PostMapping("/batch/check")
    public ResponseEntity<SimpleResponse<InventoryBatchCheckResult>>  batchCheckStock(@RequestBody @Valid InventoryBatchRequest request){
        List<Long> failedProductCodes = inventoryService.batchCheckStock(request.getStockRequestList());
        boolean allValid = failedProductCodes.isEmpty();
        return ResponseEntity.ok(
                new SimpleResponse<>(true, "", new InventoryBatchCheckResult(allValid, failedProductCodes))
        );
    }

}
