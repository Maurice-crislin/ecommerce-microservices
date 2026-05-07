package org.example.authservice.controller.advice;

import org.common.auth.dto.SimpleResponse;
import org.example.authservice.exception.AuthException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthServiceExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<SimpleResponse<Void>> handleIllegalArgumentException(IllegalArgumentException e){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(SimpleResponse.error(e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<SimpleResponse<Void>> handleIllegalStateException(IllegalStateException e){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(SimpleResponse.error(e.getMessage()));
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<SimpleResponse<Void>> handleAuthException(AuthException e){
        return ResponseEntity.status(e.getStatusCode()).body(SimpleResponse.error(e.getMessage()));
    }

    @ExceptionHandler(io.jsonwebtoken.JwtException.class)
    public ResponseEntity<SimpleResponse<Void>> handleJwtException(io.jsonwebtoken.JwtException e){
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(SimpleResponse.error("Invalid access token"));
    }
}