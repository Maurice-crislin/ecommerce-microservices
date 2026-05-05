package org.common.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SimpleResponse<T> {
    private boolean success;
    private String message;
    private T data;
    private int statusCode;

    public SimpleResponse() {}

    // 成功：无数据，无消息
    public static <T> SimpleResponse<T> success() {
        return new SimpleResponse<>(true, null, null, 200);
    }

    // 成功：无数据，只有消息（用于 register, logout 等）
    public static SimpleResponse<Void> successMessage(String message) {
        return new SimpleResponse<>(true, message, null, 200);
    }

    // 成功：有数据，无消息
    public static <T> SimpleResponse<T> success(T data) {
        return new SimpleResponse<>(true, null, data, 200);
    }

    // 成功：有数据，有消息
    public static <T> SimpleResponse<T> success(T data, String message) {
        return new SimpleResponse<>(true, message, data, 200);
    }

    // 错误：无数据，无消息
    public static <T> SimpleResponse<T> error() {
        return new SimpleResponse<>(false, null, null, 400);
    }

    // 错误：无数据，只有消息
    public static SimpleResponse<Void> error(String message) {
        return new SimpleResponse<>(false, message, null, 400);
    }

    // 错误：有数据，有消息
    public static <T> SimpleResponse<T> error(T data, String message) {
        return new SimpleResponse<>(false, message, data, 400);
    }

    // 错误：有数据，有消息，自定义状态码
    public static <T> SimpleResponse<T> error(T data, String message, int statusCode) {
        return new SimpleResponse<>(false, message, data, statusCode);
    }
}
