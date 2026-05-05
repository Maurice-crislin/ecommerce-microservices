package org.example.authservice.service;

import org.common.auth.dto.RegisterLoginRequest;
import org.example.authservice.domain.UserAccount;
import org.example.authservice.repository.UserAccountRepository;
import org.example.authservice.service.impl.RegisterServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RegisterServiceImplTest {

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private RegisterServiceImpl registerService;

    @Test
    void shouldRegisterNewUserSuccessfully() {
        RegisterLoginRequest request = new RegisterLoginRequest("test@example.com", "password123");
        when(userAccountRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("encodedPassword");

        registerService.register(request);

        verify(userAccountRepository).existsByEmail("test@example.com");
        verify(passwordEncoder).encode("password123");
        verify(userAccountRepository).save(any(UserAccount.class));
    }

    @Test
    void shouldThrowExceptionWhenEmailAlreadyExists() {
        RegisterLoginRequest request = new RegisterLoginRequest("existing@example.com", "password123");
        when(userAccountRepository.existsByEmail("existing@example.com")).thenReturn(true);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> registerService.register(request));

        assertEquals("Email already registered", exception.getMessage());
        verify(userAccountRepository, never()).save(any(UserAccount.class));
    }
}