package org.example.orderservice.exception;

import org.springframework.http.HttpStatus;

public class RetryLaterException extends RuntimeException {
    public RetryLaterException(String message) {
        super(message);
    }
}