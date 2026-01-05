package org.example.inventoryservice.exception;

// in-processing
public class OperationProcessingException extends RuntimeException {
    public OperationProcessingException(String message) {
        super(message);
    }
}
