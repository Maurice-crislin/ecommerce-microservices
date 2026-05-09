package org.example.gatewayservice;

import org.example.gatewayservice.security.RedisTokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisTokenValidatorTest {

    @Mock
    private RedisTokenService redisTokenService;

    @Test
    void shouldReturnTrueWhenUserIsBANNED() {
        when(redisTokenService.isBannedOrFrozen(1L)).thenReturn(true);
        assertTrue(redisTokenService.isBannedOrFrozen(1L));
    }

    @Test
    void shouldReturnTrueWhenUserIsFROZEN() {
        when(redisTokenService.isBannedOrFrozen(2L)).thenReturn(true);
        assertTrue(redisTokenService.isBannedOrFrozen(2L));
    }

    @Test
    void shouldReturnFalseWhenUserIsACTIVE() {
        when(redisTokenService.isBannedOrFrozen(3L)).thenReturn(false);
        assertFalse(redisTokenService.isBannedOrFrozen(3L));
    }

    @Test
    void shouldReturnFalseWhenUserIsDELETED() {
        when(redisTokenService.isBannedOrFrozen(4L)).thenReturn(false);
        assertFalse(redisTokenService.isBannedOrFrozen(4L));
    }

    @Test
    void shouldReturnFalseWhenStatusIsNull() {
        when(redisTokenService.isBannedOrFrozen(anyLong())).thenReturn(false);
        assertFalse(redisTokenService.isBannedOrFrozen(5L));
    }

    @Test
    void shouldReturnFalseWhenStatusIsEmptyString() {
        when(redisTokenService.isBannedOrFrozen(anyLong())).thenReturn(false);
        assertFalse(redisTokenService.isBannedOrFrozen(6L));
    }

    // ── isBlackedJwt tests ──

    @Test
    void shouldReturnTrueWhenJtiExistsInRedis() {
        when(redisTokenService.isBlackedJwt("jti-123")).thenReturn(true);
        assertTrue(redisTokenService.isBlackedJwt("jti-123"));
    }

    @Test
    void shouldReturnFalseWhenJtiNotInRedis() {
        when(redisTokenService.isBlackedJwt("jti-456")).thenReturn(false);
        assertFalse(redisTokenService.isBlackedJwt("jti-456"));
    }
}