package org.example.gatewayservice.security;

public interface RedisTokenService {
    boolean isBannedOrFrozen(Long userId);
    boolean isBlackedJwt(String jti);
}