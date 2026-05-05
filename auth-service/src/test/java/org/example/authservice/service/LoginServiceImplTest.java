package org.example.authservice.service;

import org.common.auth.dto.RegisterLoginRequest;
import org.common.auth.dto.TokenPair;
import org.example.authservice.domain.UserAccount;
import org.example.authservice.repository.UserAccountRepository;
import org.example.authservice.service.impl.LoginServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoginServiceImplTest {

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenService tokenService;

    @InjectMocks
    private LoginServiceImpl loginService;

    @Test
    void shouldLoginSuccessfullyWithValidCredentials() {
        RegisterLoginRequest request = new RegisterLoginRequest("test@example.com", "password123");
        UserAccount user = new UserAccount("test@example.com", "encodedPassword");
        // Simulate JPA ID generation
        user.setId(1L);

        when(userAccountRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "encodedPassword")).thenReturn(true);
        when(tokenService.generateAccessToken(1L)).thenReturn("access-token");
        when(tokenService.generateRefreshToken(1L)).thenReturn("refresh-token");

        TokenPair result = loginService.login(request);

        assertNotNull(result);
        assertEquals("access-token", result.getAccessToken());
        assertEquals("refresh-token", result.getRefreshToken());
        assertTrue(user.isActive());
        assertEquals(0, user.getFailedLoginCount());
        verify(userAccountRepository).save(user);
    }

    @Test
    void shouldThrowExceptionWhenEmailNotFound() {
        RegisterLoginRequest request = new RegisterLoginRequest("unknown@example.com", "password123");
        when(userAccountRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> loginService.login(request));

        assertEquals("Invalid email", exception.getMessage());
    }

    @Test
    void shouldThrowExceptionWhenUserIsNotActive() {
        RegisterLoginRequest request = new RegisterLoginRequest("frozen@example.com", "password123");
        UserAccount user = new UserAccount("frozen@example.com", "encodedPassword");
        // Freeze the user
        for (int i = 0; i < 5; i++) {
            user.onLoginFailure();
        }

        when(userAccountRepository.findByEmail("frozen@example.com")).thenReturn(Optional.of(user));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> loginService.login(request));

        assertEquals("Not active user", exception.getMessage());
        verify(userAccountRepository, never()).save(any(UserAccount.class));
    }

    @Test
    void shouldIncrementFailedLoginCountAndThrowOnWrongPassword() {
        RegisterLoginRequest request = new RegisterLoginRequest("test@example.com", "wrongPassword");
        UserAccount user = new UserAccount("test@example.com", "encodedPassword");

        when(userAccountRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongPassword", "encodedPassword")).thenReturn(false);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> loginService.login(request));

        assertEquals("Invalid password", exception.getMessage());
        assertEquals(1, user.getFailedLoginCount());
        verify(userAccountRepository).save(user);
    }

    @Test
    void shouldFreezeUserAfterFiveFailedAttempts() {
        RegisterLoginRequest request = new RegisterLoginRequest("test@example.com", "wrongPassword");
        UserAccount user = new UserAccount("test@example.com", "encodedPassword");
        // 4 previous failures
        for (int i = 0; i < 4; i++) {
            user.onLoginFailure();
        }

        when(userAccountRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongPassword", "encodedPassword")).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> loginService.login(request));

        assertEquals(5, user.getFailedLoginCount());
        assertFalse(user.isActive());
    }
}