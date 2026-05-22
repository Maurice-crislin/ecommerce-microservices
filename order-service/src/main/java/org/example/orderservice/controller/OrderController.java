package org.example.orderservice.controller;

import lombok.RequiredArgsConstructor;
import org.example.orderservice.dto.OrderRequest;
import org.example.orderservice.dto.SimpleResponse;
import org.example.orderservice.exception.RetryLaterException;
import org.example.orderservice.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<SimpleResponse<Void>> createOrder(@RequestBody OrderRequest request) {
        try {
            orderService.createOrder(request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new SimpleResponse<>(true, "Order created successfully"));
        } catch (RetryLaterException e) { // 需要客户端退避重试
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new SimpleResponse<>(false, e.getMessage()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new SimpleResponse<>(false, e.getMessage()));
        }

    }

    @PostMapping("/{orderId}/pay")
    public ResponseEntity<SimpleResponse<Void>> payOrder(@PathVariable Long orderId) {
        try {
            orderService.payOrder(orderId);
            return ResponseEntity.ok(new SimpleResponse<>(true, "Payment initiated successfully"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new SimpleResponse<>(false, e.getMessage()));
        }
    }
}