package org.example.orderservice.client;

import org.common.payment.dto.PaymentRequest;

import org.common.payment.dto.PaymentResponse;
import org.common.payment.dto.RefundResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class PaymentClient {
    @Autowired
    private WebClient.Builder webClientBuilder;

    @Value("${services.payment.base-url}")
    private String baseUrl;

    public PaymentResponse payment(PaymentRequest paymentRequest) {

        return webClientBuilder
                .baseUrl(baseUrl)
                .build()
                .post()
                .uri("/payment")
                .bodyValue(paymentRequest)
                .retrieve()
                .bodyToMono(PaymentResponse.class)
                .block();
    }
    public RefundResponse refund(String paymentNo) {

        return webClientBuilder
                .baseUrl(baseUrl)
                .build()
                .get()
                .uri("/payment/refund/{paymentNo}",paymentNo)
                .retrieve()
                .bodyToMono(RefundResponse.class)
                .block();
    }
}
