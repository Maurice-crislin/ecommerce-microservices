package org.example.ecomGateway.security;

import lombok.RequiredArgsConstructor;
import org.common.auth.security.JwtValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter implements GlobalFilter, Ordered {

    @Value("${auth.whitelist}")
    private List<String> WHITE_LIST;

    private final JwtValidator jwtValidator;
    private final RedisTokenService redisTokenService;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain filterChain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        String path = exchange.getRequest().getURI().getPath();
        ServerHttpResponse response = exchange.getResponse();

        // Whitelist check
        if (WHITE_LIST.stream().anyMatch(pattern -> pathMatcher.match(pattern, path))) {
            return filterChain.filter(exchange);
        }

        // Missing or invalid Authorization header
        if (authHeader == null || authHeader.trim().isEmpty() || !authHeader.startsWith("Bearer ")) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return response.setComplete();
        }

        String jwtToken = authHeader.substring(7);

        if (jwtToken.trim().isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return response.setComplete();
        }

        // 1. Validate JWT and get userId
        Long userId = jwtValidator.validateAndGetUserId(jwtToken);

        // 2. Check JTI blacklist in Redis
        String jti = jwtValidator.getIdFromJwtToken(jwtToken);
        if (redisTokenService.isBlackedJwt(jti)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return response.setComplete();
        }

        // 3. Check account status from Redis
        if (redisTokenService.isBannedOrFrozen(userId)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return response.setComplete();
        }

        // 4. Mutate request with headers and forward
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header("X-User-Id", String.valueOf(userId))
                .header("X-Jti", jti)
                .build();

        return filterChain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        return 0;
    }
}