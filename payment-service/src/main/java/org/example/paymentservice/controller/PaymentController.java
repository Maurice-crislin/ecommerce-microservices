package org.example.paymentservice.controller;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.paymentservice.dto.PaymentRequest;
import org.example.paymentservice.dto.PaymentResponse;
import org.example.paymentservice.model.PaymentStatus;
import org.example.paymentservice.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payment")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentService paymentService;
    @PostMapping
    public ResponseEntity<PaymentResponse> payment(@RequestBody @Valid PaymentRequest request){
        String paymentNo = paymentService.processPayment(request);
        return ResponseEntity.accepted().body(
                new PaymentResponse(paymentNo, PaymentStatus.PENDING,"Payment request accepted")
        );
    }
}
