package org.example.authservice.service;

import org.common.auth.dto.TokenPair;
import org.example.authservice.domain.RefreshToken;
import org.example.authservice.repository.RefreshTokenRepository;
import org.common.auth.security.JwtValidator;
import org.example.authservice.security.JwtProvider;
import org.example.authservice.service.impl.TokenServiceImpl;
import org.example.authservice.service.UserStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenServiceImplTest {

    @Mock
    private JwtProvider jwtProvider;

    @Mock
    private JwtValidator jwtValidator;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private UserStatusService userStatusService;

    @InjectMocks
    private TokenServiceImpl tokenService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(tokenService, "refreshExpirationDays", 7);
    }

    @Test
    void shouldGenerateAccessToken() {
        when(jwtProvider.generateJwtToken("1")).thenReturn("generated-jwt-token");

        String token = tokenService.generateAccessToken(1L);

        assertEquals("generated-jwt-token", token);
        verify(jwtProvider).generateJwtToken("1");
    }

    @Test
    void shouldGenerateRefreshTokenAndSaveToDatabase() {
        String result = tokenService.generateRefreshToken(1L);

        assertNotNull(result);
        assertFalse(result.isEmpty());
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void shouldValidateJwtToken() {
        String token = "test-jwt-token";
        when(jwtValidator.validateJwtToken(token)).thenReturn(true);

        Boolean result = tokenService.validateJwtToken(token);

        assertTrue(result);
        verify(jwtValidator).validateJwtToken(token);
    }

    @Test
    void shouldRejectInvalidJwtToken() {
        String token = "invalid-jwt-token";
        when(jwtValidator.validateJwtToken(token)).thenReturn(false);

        Boolean result = tokenService.validateJwtToken(token);

        assertFalse(result);
    }

    @Test
    void shouldRegenerateBothTokensAndRevokeOldRefreshToken() {
        String oldRefreshToken = "old-refresh-token";
        Long userId = 1L;
        RefreshToken oldTokenEntity = new RefreshToken(userId, oldRefreshToken,
                Instant.now().plus(Duration.ofDays(7)));

        when(refreshTokenRepository.findByToken(oldRefreshToken)).thenReturn(Optional.of(oldTokenEntity));
        when(userStatusService.isBannedOrFrozen(userId)).thenReturn(false);
        when(jwtProvider.generateJwtToken("1")).thenReturn("new-access-token");

        TokenPair result = tokenService.regenerateBothToken(oldRefreshToken);

        assertNotNull(result);
        assertNotNull(result.getAccessToken());
        assertNotNull(result.getRefreshToken());
        assertTrue(oldTokenEntity.isRevoked());
        verify(refreshTokenRepository).save(oldTokenEntity);
        verify(refreshTokenRepository, times(2)).save(any(RefreshToken.class)); // old revoked + new saved
    }

    @Test
    void shouldThrowExceptionWhenOldRefreshTokenNotFound() {
        String oldRefreshToken = "unknown-token";
        when(refreshTokenRepository.findByToken(oldRefreshToken)).thenReturn(Optional.empty());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> tokenService.regenerateBothToken(oldRefreshToken));

        assertEquals("not valid refresh token", exception.getMessage());
    }

    @Test
    void shouldThrowExceptionWhenOldRefreshTokenIsExpired() {
        String oldRefreshToken = "expired-token";
        RefreshToken expiredToken = new RefreshToken(1L, oldRefreshToken,
                Instant.now().minus(Duration.ofDays(1))); // Expired

        when(refreshTokenRepository.findByToken(oldRefreshToken)).thenReturn(Optional.of(expiredToken));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> tokenService.regenerateBothToken(oldRefreshToken));

        assertEquals("refresh token is invalid", exception.getMessage());
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    void shouldThrowExceptionWhenOldRefreshTokenIsRevoked() {
        String oldRefreshToken = "revoked-token";
        RefreshToken revokedToken = new RefreshToken(1L, oldRefreshToken,
                Instant.now().plus(Duration.ofDays(7)));
        revokedToken.revoke();

        when(refreshTokenRepository.findByToken(oldRefreshToken)).thenReturn(Optional.of(revokedToken));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> tokenService.regenerateBothToken(oldRefreshToken));

        assertEquals("refresh token is invalid", exception.getMessage());
    }

    @Test
    void shouldThrowExceptionWhenUserIsBanned() {
        String oldRefreshToken = "banned-user-token";
        Long userId = 1L;
        RefreshToken tokenEntity = new RefreshToken(userId, oldRefreshToken,
                Instant.now().plus(Duration.ofDays(7)));

        when(refreshTokenRepository.findByToken(oldRefreshToken)).thenReturn(Optional.of(tokenEntity));
        when(userStatusService.isBannedOrFrozen(userId)).thenReturn(true);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> tokenService.regenerateBothToken(oldRefreshToken));

        assertEquals("User has been banned", exception.getMessage());
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    void shouldThrowExceptionWhenUserIsFrozen() {
        String oldRefreshToken = "frozen-user-token";
        Long userId = 1L;
        RefreshToken tokenEntity = new RefreshToken(userId, oldRefreshToken,
                Instant.now().plus(Duration.ofDays(7)));

        when(refreshTokenRepository.findByToken(oldRefreshToken)).thenReturn(Optional.of(tokenEntity));
        when(userStatusService.isBannedOrFrozen(userId)).thenReturn(true);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> tokenService.regenerateBothToken(oldRefreshToken));

        assertEquals("User has been banned", exception.getMessage());
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }
}