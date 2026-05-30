package com.sicame.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Generic API response wrapper used for operation confirmations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse {
    private String  status;
    private String  message;
    private Object  data;
    private LocalDateTime timestamp = LocalDateTime.now();

    public static ApiResponse ok(String message) {
        return new ApiResponse("ok", message, null, LocalDateTime.now());
    }

    public static ApiResponse ok(String message, Object data) {
        return new ApiResponse("ok", message, data, LocalDateTime.now());
    }

    public static ApiResponse error(String message) {
        return new ApiResponse("error", message, null, LocalDateTime.now());
    }
}
