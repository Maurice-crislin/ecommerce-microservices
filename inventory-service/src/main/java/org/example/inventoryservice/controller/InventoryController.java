package org.example.inventoryservice.controller;

import jakarta.persistence.OptimisticLockException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.inventoryservice.dto.StockRequest;
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
    @PostMapping("/lock")
    public ResponseEntity<String>  lockStock(@RequestBody @Valid StockRequest request){
        try {
            // 200
            inventoryService.lockStock(request.getProductCode(), request.getQuantity());
            return ResponseEntity.ok("stock has been successfully locked");
        } catch (IllegalArgumentException e) {
            // 400
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (OptimisticLockException e){
            // Return 409 Conflict when concurrent updates cause a version conflict
            return ResponseEntity.status(409).body("Concurrent update, please retry");
        } catch (Exception e) {
            // 500
            return ResponseEntity.internalServerError().body("Internal server error");
        }
    }
    @PostMapping("/unlock")
    public ResponseEntity<String>  unlockStock(@RequestBody @Valid StockRequest request){
        try {
            // 200
            inventoryService.unlockStock(request.getProductCode(), request.getQuantity());
            return ResponseEntity.ok("stock has been successfully unlocked");
        } catch (IllegalArgumentException e) {
            // 400
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (OptimisticLockException e){
            // Return 409 Conflict when concurrent updates cause a version conflict
            return ResponseEntity.status(409).body("Concurrent update, please retry");
        } catch (Exception e) {
            // 500
            return ResponseEntity.internalServerError().body("Internal server error");
        }
    }
    @PostMapping("/confirm")
    public ResponseEntity<String>  confirmSale(@RequestBody @Valid StockRequest request){
        try {
            // 200
            inventoryService.confirmSale(request.getProductCode(), request.getQuantity());
            return ResponseEntity.ok("sale has been successfully confirmed");
        } catch (IllegalArgumentException e) {
            // 400
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (OptimisticLockException e){
            // Return 409 Conflict when concurrent updates cause a version conflict
            return ResponseEntity.status(409).body("Concurrent update, please retry");
        } catch (Exception e) {
            // 500
            return ResponseEntity.internalServerError().body("Internal server error");
        }
    }

}
