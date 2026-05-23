package org.example.paymentservice.controller;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.common.payment.dto.RefundResponse;
import org.common.payment.enums.RefundStatus;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.dto.PaymentResponse;
import org.common.payment.enums.PaymentStatus;

import org.example.paymentservice.model.Payment;
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
        // 同步返回最终状态的 Payment
        Payment payment = paymentService.createPayment(request);
        // 根据最终状态决定 HTTP 状态码和响应消息
        if (payment.getStatus() == PaymentStatus.PAID) {
            return ResponseEntity.ok(new PaymentResponse(payment.getPaymentNo(), PaymentStatus.PAID, "Payment successful"));
        } else {
            // 支付失败，标记为失败
            return ResponseEntity.badRequest().body(new PaymentResponse(payment.getPaymentNo(), PaymentStatus.FAILED, "Payment failed"));
        }
    }
    // GET /payment/refund/{paymentNo}
    @GetMapping("/refund/{paymentNo}")
    public ResponseEntity<RefundResponse> refund(@PathVariable String paymentNo){
        String refundNo = refundService.processRefund(paymentNo);
        return  ResponseEntity.accepted().body(
                new RefundResponse(refundNo, RefundStatus.PROCESSING, "Refund request accepted")
        );
    }
    // GET /payment/refund/check/{refundNo}
    @GetMapping("/refund/check/{refundNo}")
    public ResponseEntity<RefundResponse> refundCheck(@PathVariable String refundNo){
        RefundStatus refundStatus = refundService.checkRefundStatus(refundNo);
        return  ResponseEntity.accepted().body(
                new RefundResponse(refundNo, refundStatus, "")
        );
    }
}
