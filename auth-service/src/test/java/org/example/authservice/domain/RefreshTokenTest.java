package org.example.authservice.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RefreshTokenTest {

    @Test
    void shouldCreateValidRefreshToken() {
        Instant expiry = Instant.now().plus(Duration.ofDays(7));
        RefreshToken token = new RefreshToken(1L, "test-token-uuid", expiry);
        
        assertEquals(1L, token.getUserId());
        assertEquals("test-token-uuid", token.getToken());
        assertEquals(expiry, token.getExpiredAt());
        assertFalse(token.isRevoked());
        assertTrue(token.checkValid());
    }

    @Test
    void shouldBeInvalidWhenRevoked() {
        RefreshToken token = new RefreshToken(1L, "test-token", 
                Instant.now().plus(Duration.ofDays(7)));
        
        token.revoke();
        
        assertTrue(token.isRevoked());
        assertNotNull(token.getRevokedAt());
        assertFalse(token.checkValid());
    }

    @Test
    void shouldBeInvalidWhenExpired() {
        RefreshToken token = new RefreshToken(1L, "test-token", 
                Instant.now().minus(Duration.ofDays(1)));  // Already expired
        
        assertFalse(token.checkValid());
    }

    @Test
    void shouldBeValidWhenNotRevokedAndNotExpired() {
        RefreshToken token = new RefreshToken(1L, "test-token", 
                Instant.now().plus(Duration.ofDays(7)));
        
        assertFalse(token.isRevoked());
        assertTrue(token.checkValid());
    }
}