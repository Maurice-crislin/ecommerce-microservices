package org.example.inventoryservice.controller.advice;

import org.example.inventoryservice.dto.SimpleResponse;
import org.example.inventoryservice.exception.OperationProcessingException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // 400
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<SimpleResponse<Object>> handleIllegalArgumentOrStatus(Exception e) {
        return ResponseEntity.badRequest().body(new SimpleResponse<>(
                false, e.getMessage()
        ));
    }

    // 400 @valid
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<SimpleResponse<Object>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .orElse("Validation failed");

        return ResponseEntity.badRequest()
                .body(new SimpleResponse<>(false, msg));
    }


    // 409
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<SimpleResponse<Object>> handleOptimisticLockFailure(OptimisticLockingFailureException e) {
        return ResponseEntity.status(409).body(new SimpleResponse<>(
                false,"stock add failed due to concurrent update. pls retry"
        ));
    }

    // 409
    @ExceptionHandler(OperationProcessingException.class)
    public ResponseEntity<SimpleResponse<Object>> handleProcessing(OperationProcessingException e) {
        return ResponseEntity.status(409).body(new SimpleResponse<>(
                false,e.getMessage()
        ));
    }

    // 500
    @ExceptionHandler(Exception.class)
    public ResponseEntity<SimpleResponse<Object>> handleOthers(Exception e) {
        return ResponseEntity.internalServerError().body(new SimpleResponse<>(
                false,"Internal server error"
        ));
    }
}
