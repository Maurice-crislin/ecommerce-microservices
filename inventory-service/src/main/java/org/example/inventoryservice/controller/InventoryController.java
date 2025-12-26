package org.example.inventoryservice.controller;

import jakarta.persistence.OptimisticLockException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.inventoryservice.dto.DeductStockRequest;
import org.example.inventoryservice.service.InventoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/inventories")
@RequiredArgsConstructor
public class InventoryController {
    private final InventoryService inventoryService;
    @PostMapping("/deduct")
    public ResponseEntity<String> deductStock(@RequestBody @Valid DeductStockRequest request){
        try {
            // 200
            inventoryService.deductStock(request.getProductCode(), request.getQuantity());
            return ResponseEntity.ok("stock has been successfully deducted");
        } catch (IllegalArgumentException e) {
            // 400
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (OptimisticLockException e){
            // Return 409 Conflict when concurrent updates cause a version conflict
            return ResponseEntity.status(409).body("stock deduction failed due to concurrent update. pls retry");
        } catch (Exception e) {
            // 500
            return ResponseEntity.internalServerError().body("Internal server error");
        }

    }
}
