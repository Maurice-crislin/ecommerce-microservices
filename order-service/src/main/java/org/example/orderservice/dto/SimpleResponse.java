package org.example.orderservice.dto;

import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class SimpleResponse<T> {
    private boolean success;
    private String message;
    private T data;

    public SimpleResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
        this.data = null; // default
    }
}
