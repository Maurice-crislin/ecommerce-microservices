package org.example.orderservice.client;

import org.common.inventory.dto.InventoryBatchRequest;
import org.example.orderservice.dto.SimpleResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class InventoryClient {
    @Autowired
    private WebClient.Builder webClientBuilder;

    @Value("${services.inventory.base-url}")
    private String baseUrl;

    public SimpleResponse batchLockInventory(InventoryBatchRequest batchLockRequest) {

        return webClientBuilder
                .baseUrl(baseUrl)
                .build()
                .post()
                .uri("/batch/lock")
                .bodyValue(batchLockRequest)
                .retrieve()
                .bodyToMono(SimpleResponse.class)
                .block();
    }

}
