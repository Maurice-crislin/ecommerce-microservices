package org.example.authservice.controller.advice;

import org.common.auth.dto.SimpleResponse;
import org.example.authservice.exception.AuthException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceExceptionHandlerTest {

    private final AuthServiceExceptionHandler handler = new AuthServiceExceptionHandler();

    @Test
    void shouldReturnBadRequestForIllegalStateException() {
        IllegalStateException exception = new IllegalStateException("Some state error");

        ResponseEntity<SimpleResponse<Void>> response = handler.handleIllegalStateException(exception);

        assertEquals(400, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isSuccess());
        assertEquals("Some state error", response.getBody().getMessage());
    }

    @Test
    void shouldReturnBadRequestForIllegalArgumentException() {
        IllegalArgumentException exception = new IllegalArgumentException("Invalid argument");

        ResponseEntity<SimpleResponse<Void>> response = handler.handleIllegalArgumentException(exception);

        assertEquals(400, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isSuccess());
        assertEquals("Invalid argument", response.getBody().getMessage());
    }

    @Test
    void shouldReturnUnauthorizedForAuthException() {
        AuthException exception = new AuthException("Unauthorized access");

        ResponseEntity<SimpleResponse<Void>> response = handler.handleAuthException(exception);

        assertEquals(401, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isSuccess());
        assertEquals("Unauthorized access", response.getBody().getMessage());
    }

    @Test
    void shouldHandleAuthExceptionWithCustomStatusCode() {
        AuthException exception = new AuthException("Custom error", 403);

        ResponseEntity<SimpleResponse<Void>> response = handler.handleAuthException(exception);

        assertEquals(403, response.getStatusCodeValue());
    }
}