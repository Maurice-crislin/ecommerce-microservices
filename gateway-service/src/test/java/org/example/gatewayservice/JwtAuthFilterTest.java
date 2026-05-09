package org.example.gatewayservice;

import org.example.gatewayservice.config.SecurityProperties;
import org.example.gatewayservice.security.JwtAuthFilter;
import org.example.gatewayservice.security.RedisTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class JwtAuthFilterTest {

    private static final String VALID_TOKEN = "VALID_JWT_PLACEHOLDER";
    private static final String DUMMY_JTI = "test-jti-12345";
    private static final Long USER_ID = 123L;

    private JwtAuthFilter filter;
    private TestRedisTokenService redisService = new TestRedisTokenService();
    private SecurityProperties securityProperties;
    private TestJwtValidator jwtValidator;

    @BeforeEach
    void setUp() {
        securityProperties = new SecurityProperties();
        jwtValidator = new TestJwtValidator();
        redisService = new TestRedisTokenService();
        filter = new JwtAuthFilter(securityProperties, jwtValidator, redisService);
        securityProperties.setWhitelist(Arrays.asList("/api/auth/login/**", "/api/auth/refresh/**", "/api/auth/register/**"));
    }

    // ── Whitelist ──

    @Test
    void whitelistLoginShouldPass() {
        TestContext ctx = createContext("/api/auth/login", null);
        filter.filter(ctx.exchange, ctx.filterChain);
        assertTrue(ctx.forwarded());
    }

    @Test
    void whitelistRefreshShouldPass() {
        TestContext ctx = createContext("/api/auth/refresh", null);
        filter.filter(ctx.exchange, ctx.filterChain);
        assertTrue(ctx.forwarded());
    }

    @Test
    void whitelistRegisterShouldPass() {
        TestContext ctx = createContext("/api/auth/register", null);
        filter.filter(ctx.exchange, ctx.filterChain);
        assertTrue(ctx.forwarded());
    }

    @Test
    void whitelistSubpathShouldPass() {
        TestContext ctx = createContext("/api/auth/login/extra/subpath", null);
        filter.filter(ctx.exchange, ctx.filterChain);
        assertTrue(ctx.forwarded());
    }

    // ── Auth header missing/invalid ──

    @Test
    void noAuthHeaderShouldReturn401() {
        TestContext ctx = createContext("/api/orders", null);
        filter.filter(ctx.exchange, ctx.filterChain);
        assertFalse(ctx.forwarded());
        assertEquals(HttpStatus.UNAUTHORIZED.value(), ctx.statusCode());
    }

    @Test
    void emptyAuthHeaderShouldReturn401() {
        TestContext ctx = createContext("/api/orders", "");
        filter.filter(ctx.exchange, ctx.filterChain);
        assertEquals(HttpStatus.UNAUTHORIZED.value(), ctx.statusCode());
    }

    @Test
    void nonBearerAuthShouldReturn401() {
        TestContext ctx = createContext("/api/orders", "Basic token");
        filter.filter(ctx.exchange, ctx.filterChain);
        assertEquals(HttpStatus.UNAUTHORIZED.value(), ctx.statusCode());
    }

    @Test
    void emptyBearerTokenShouldReturn401() {
        TestContext ctx = createContext("/api/orders", "Bearer ");
        filter.filter(ctx.exchange, ctx.filterChain);
        assertEquals(HttpStatus.UNAUTHORIZED.value(), ctx.statusCode());
    }

    // ── Valid auth ──

    @Test
    void validAuthShouldForward() {
        redisService.setBlacklisted(false);
        redisService.setBannedOrFrozen(false);
        TestContext ctx = createContext("/api/orders", "Bearer " + VALID_TOKEN);
        filter.filter(ctx.exchange, ctx.filterChain);
        assertTrue(ctx.forwarded());
        assertEquals(USER_ID.toString(), ctx.exchange.getRequest().getHeaders().getFirst("X-User-Id"));
        assertEquals(DUMMY_JTI, ctx.exchange.getRequest().getHeaders().getFirst("X-Jti"));
    }

    // ── Blacklisted JWT ──

    @Test
    void blacklistedTokenShouldBeRejected() {
        redisService.setBlacklisted(true);
        redisService.setBannedOrFrozen(false);
        TestContext ctx = createContext("/api/orders", "Bearer " + VALID_TOKEN);
        filter.filter(ctx.exchange, ctx.filterChain);
        assertFalse(ctx.forwarded());
        assertEquals(HttpStatus.UNAUTHORIZED.value(), ctx.statusCode());
    }

    // ── Banned/frozen user ──

    @Test
    void bannedUserShouldBeRejected() {
        redisService.setBlacklisted(false);
        redisService.setBannedOrFrozen(true);
        TestContext ctx = createContext("/api/orders", "Bearer " + VALID_TOKEN);
        filter.filter(ctx.exchange, ctx.filterChain);
        assertFalse(ctx.forwarded());
        assertEquals(HttpStatus.UNAUTHORIZED.value(), ctx.statusCode());
    }

    @Test
    void frozenUserShouldBeRejected() {
        redisService.setBlacklisted(false);
        redisService.setBannedOrFrozen(true);
        TestContext ctx = createContext("/api/orders", "Bearer " + VALID_TOKEN);
        filter.filter(ctx.exchange, ctx.filterChain);
        assertFalse(ctx.forwarded());
        assertEquals(HttpStatus.UNAUTHORIZED.value(), ctx.statusCode());
    }

    // ── Invalid JWT ──

    @Test
    void invalidJwtShouldThrowException() {
        TestContext ctx = createContext("/api/orders", "Bearer invalid.jwt.token");
        assertThrows(io.jsonwebtoken.JwtException.class,
                () -> filter.filter(ctx.exchange, ctx.filterChain));
    }

    // ── Order ──

    @Test
    void shouldHaveOrderZero() {
        assertEquals(0, filter.getOrder());
    }

    // ── Helper class ──

    static class TestContext {
        final ServerWebExchange exchange;
        final GatewayFilterChain filterChain;
        final AtomicBoolean forwardedFlag;

        TestContext(ServerWebExchange exchange, GatewayFilterChain filterChain, AtomicBoolean forwardedFlag) {
            this.exchange = exchange;
            this.filterChain = filterChain;
            this.forwardedFlag = forwardedFlag;
        }

        boolean forwarded() { return forwardedFlag.get(); }
        int statusCode() {
            return exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value() : -1;
        }
    }

    private TestContext createContext(String path, String authHeader) {
        MockServerHttpRequest.BaseBuilder<?> reqBuilder = MockServerHttpRequest.get(path);
        if (authHeader != null && !authHeader.isEmpty()) {
            reqBuilder.header("Authorization", authHeader);
        }
        ServerWebExchange exchange = MockServerWebExchange.from(reqBuilder.build());
        AtomicBoolean forwarded = new AtomicBoolean(false);
        GatewayFilterChain chain = new GatewayFilterChain() {
            @Override
            public Mono<Void> filter(ServerWebExchange e) {
                forwarded.set(true);
                return Mono.empty();
            }
        };
        return new TestContext(exchange, chain, forwarded);
    }

    static class TestRedisTokenService implements RedisTokenService {
        private boolean blacklisted = false;
        private boolean bannedOrFrozen = false;

        void setBlacklisted(boolean v) { this.blacklisted = v; }
        void setBannedOrFrozen(boolean v) { this.bannedOrFrozen = v; }
        @Override public boolean isBlackedJwt(String jti) { return blacklisted; }
        @Override public boolean isBannedOrFrozen(Long userId) { return bannedOrFrozen; }
    }
}