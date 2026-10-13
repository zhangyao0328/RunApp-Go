package com.runpulse.server.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码与裸 JSON {"error": message} 中的 message。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public HttpStatus getStatus() {
        return status;
    }
}
