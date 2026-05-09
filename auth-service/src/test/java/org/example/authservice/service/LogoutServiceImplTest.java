package org.example.authservice.service;

import org.common.auth.security.JwtValidator;
import org.example.authservice.domain.RefreshToken;
import org.example.authservice.exception.AuthException;
import org.example.authservice.repository.RefreshTokenRepository;
import org.example.authservice.service.impl.LogoutServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LogoutServiceImplTest {

    @Mock
    private JwtValidator jwtValidator;

    @Mock
    private UserStatusService userStatusService;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private LogoutServiceImpl logoutService;

    @Test
    void shouldLogoutSuccessfully() {
        String jwtToken = "valid-jwt-token";
        String refreshToken = "valid-refresh-token";
        Long userId = 1L;
        String jti = "uuid-jti-123";

        RefreshToken refreshTokenEntity = new RefreshToken(userId, refreshToken,
                Instant.now().plus(Duration.ofDays(7)));

        when(jwtValidator.validateAndGetUserId(jwtToken)).thenReturn(userId);
        when(jwtValidator.getIdFromJwtToken(jwtToken)).thenReturn(jti);
        when(refreshTokenRepository.findByToken(refreshToken)).thenReturn(Optional.of(refreshTokenEntity));

        logoutService.logout(jwtToken, refreshToken);

        // Verify refresh token is revoked
        assertTrue(refreshTokenEntity.isRevoked());
        verify(refreshTokenRepository).save(refreshTokenEntity);

        // Verify jti is blacklisted
        verify(userStatusService).blackJwtByJti(jti);
    }

    @Test
    void shouldThrowExceptionWhenJwtTokenIsInvalid() {
        String jwtToken = "invalid-jwt-token";

        when(jwtValidator.validateAndGetUserId(jwtToken))
                .thenThrow(new io.jsonwebtoken.JwtException("Invalid token"));

        assertThrows(io.jsonwebtoken.JwtException.class,
                () -> logoutService.logout(jwtToken, "some-refresh-token"));

        verify(refreshTokenRepository, never()).findByToken(anyString());
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
        verify(userStatusService, never()).blackJwtByJti(anyString());
    }

    @Test
    void shouldThrowExceptionWhenRefreshTokenNotFound() {
        String jwtToken = "valid-jwt-token";
        String refreshToken = "unknown-refresh-token";

        when(jwtValidator.validateAndGetUserId(jwtToken)).thenReturn(1L);
        when(refreshTokenRepository.findByToken(refreshToken)).thenReturn(Optional.empty());

        AuthException exception = assertThrows(AuthException.class,
                () -> logoutService.logout(jwtToken, refreshToken));

        assertEquals("Invalid refresh token", exception.getMessage());
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
        verify(userStatusService, never()).blackJwtByJti(anyString());
    }

    @Test
    void shouldThrowExceptionWhenRefreshTokenDoesNotMatchUser() {
        String jwtToken = "valid-jwt-token";
        String refreshToken = "other-user-refresh-token";

        RefreshToken refreshTokenEntity = new RefreshToken(2L, refreshToken, // userId=2
                Instant.now().plus(Duration.ofDays(7)));

        when(jwtValidator.validateAndGetUserId(jwtToken)).thenReturn(1L); // JWT says userId=1
        when(refreshTokenRepository.findByToken(refreshToken)).thenReturn(Optional.of(refreshTokenEntity));

        AuthException exception = assertThrows(AuthException.class,
                () -> logoutService.logout(jwtToken, refreshToken));

        assertEquals("Refresh token doesn't match this user", exception.getMessage());
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
        verify(userStatusService, never()).blackJwtByJti(anyString());
    }
}