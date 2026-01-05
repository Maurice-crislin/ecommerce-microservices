package org.example.inventoryservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.common.inventory.dto.StockRequest;
import org.example.inventoryservice.dto.SimpleResponse;
import org.example.inventoryservice.service.InventoryService;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/inventories")
@RequiredArgsConstructor
public class AdminInventoryController {
    private final InventoryService inventoryService;

    @PostMapping("/deduct")
    // POST /admin/inventories/deduct
    public ResponseEntity<SimpleResponse<Object>> deductStock(@RequestBody @Valid StockRequest request){
        // 200
        inventoryService.deductStockDirectly(request.getProductCode(), request.getQuantity());
        return ResponseEntity.ok(
                new SimpleResponse<>(
                        true, "stock has been successfully deducted"
                )
        );

    }
    @PostMapping("/add")
    // POST /admin/inventories/add
    public ResponseEntity<SimpleResponse<Object>> addStock(@RequestBody @Valid StockRequest request){
        // 200
        inventoryService.addStock(request.getProductCode(), request.getQuantity());
        return ResponseEntity.ok(
                new SimpleResponse<>(
                        true, "stock has been successfully added"
                )
        );
    }
}
