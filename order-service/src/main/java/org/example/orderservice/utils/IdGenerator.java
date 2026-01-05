package org.example.orderservice.utils;


import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class IdGenerator {
    public Long generateOrderId() {
        UUID uuid = UUID.randomUUID();
        return Math.abs(uuid.getMostSignificantBits()); // high 64 bits
    }
}
