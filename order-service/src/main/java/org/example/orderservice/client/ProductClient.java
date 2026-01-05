package org.example.orderservice.client;

import org.common.product.dto.BatchProductPriceRequest;
import org.common.product.dto.BatchProductPriceResponse;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Component
public class ProductClient {
    @Autowired
    private WebClient.Builder webClientBuilder;

    @Value("${services.product.base-url}")
    private String baseUrl;

    public BatchProductPriceResponse getBatchPrice(BatchProductPriceRequest batchProductPriceRequest) {

        return webClientBuilder
                .baseUrl(baseUrl)
                .build()
                .post()
                .uri("/products/batch")
                .bodyValue(batchProductPriceRequest)
                .retrieve()
                .bodyToMono(BatchProductPriceResponse.class)
                .block();
    }
}
