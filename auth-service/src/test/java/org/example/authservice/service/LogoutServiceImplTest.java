package org.example.authservice.service;

import org.common.auth.utils.JwtValidator;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LogoutServiceImplTest {

    @Mock
    private TokenService tokenService;

    @Mock
    private JwtValidator jwtValidator;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private LogoutServiceImpl logoutService;

    @Test
    void shouldLogoutSuccessfully() {
        String jwtToken = "valid-jwt-token";
        String refreshToken = "valid-refresh-token";
        Long userId = 1L;

        RefreshToken refreshTokenEntity = new RefreshToken(userId, refreshToken,
                Instant.now().plus(Duration.ofDays(7)));

        when(tokenService.validateJwtToken(jwtToken)).thenReturn(true);
        when(jwtValidator.validateAndGetUserId(jwtToken)).thenReturn(userId);
        when(refreshTokenRepository.findByToken(refreshToken)).thenReturn(Optional.of(refreshTokenEntity));

        logoutService.logout(jwtToken, refreshToken);

        assertTrue(refreshTokenEntity.isRevoked());
        verify(refreshTokenRepository).save(refreshTokenEntity);
    }

    @Test
    void shouldThrowExceptionWhenJwtTokenIsInvalid() {
        String jwtToken = "invalid-jwt-token";
        String refreshToken = "some-refresh-token";

        when(tokenService.validateJwtToken(jwtToken)).thenReturn(false);

        AuthException exception = assertThrows(AuthException.class,
                () -> logoutService.logout(jwtToken, refreshToken));

        assertEquals("Invalid access token", exception.getMessage());
        verify(refreshTokenRepository, never()).findByToken(anyString());
    }

    @Test
    void shouldThrowExceptionWhenRefreshTokenNotFound() {
        String jwtToken = "valid-jwt-token";
        String refreshToken = "unknown-refresh-token";

        when(tokenService.validateJwtToken(jwtToken)).thenReturn(true);
        when(jwtValidator.validateAndGetUserId(jwtToken)).thenReturn(1L);
        when(refreshTokenRepository.findByToken(refreshToken)).thenReturn(Optional.empty());

        AuthException exception = assertThrows(AuthException.class,
                () -> logoutService.logout(jwtToken, refreshToken));

        assertEquals("Invalid refresh token", exception.getMessage());
    }

    @Test
    void shouldThrowExceptionWhenRefreshTokenDoesNotMatchUser() {
        String jwtToken = "valid-jwt-token";
        String refreshToken = "other-user-refresh-token";

        RefreshToken refreshTokenEntity = new RefreshToken(2L, refreshToken, // userId=2
                Instant.now().plus(Duration.ofDays(7)));

        when(tokenService.validateJwtToken(jwtToken)).thenReturn(true);
        when(jwtValidator.validateAndGetUserId(jwtToken)).thenReturn(1L); // JWT says userId=1
        when(refreshTokenRepository.findByToken(refreshToken)).thenReturn(Optional.of(refreshTokenEntity));

        AuthException exception = assertThrows(AuthException.class,
                () -> logoutService.logout(jwtToken, refreshToken));

        assertEquals("Refresh token doesn't match this user", exception.getMessage());
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }
}