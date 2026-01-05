package org.example.paymentservice.controller;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.common.payment.dto.RefundResponse;
import org.common.payment.enums.RefundStatus;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.dto.PaymentResponse;
import org.common.payment.enums.PaymentStatus;

import org.example.paymentservice.service.PaymentService;
import org.example.paymentservice.service.RefundService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/payment")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentService paymentService;
    private final RefundService refundService;
    @PostMapping
    public ResponseEntity<PaymentResponse> payment(@RequestBody @Valid PaymentRequest request){
        String paymentNo = paymentService.createPayment(request);
        return ResponseEntity.accepted().body(
                new PaymentResponse(paymentNo, PaymentStatus.PROCESSING,"Payment request accepted")
        );
    }
    // GET /payment/refund/{paymentNo}
    @GetMapping("/refund/{paymentNo}")
    public ResponseEntity<RefundResponse> refund(@PathVariable String paymentNo){
        String refundNo = refundService.processRefund(paymentNo);
        return  ResponseEntity.accepted().body(
                new RefundResponse(refundNo, RefundStatus.PROCESSING, "Refund request accepted")
        );
    }
}
