package com.runpulse.server.exception;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理，保证错误响应统一为裸 JSON {"error":"..."}，与 Go 版本 writeJSON 行为一致。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    /** 请求体不是合法 JSON：400 {"error":"bad request"} */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleNotReadable(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, "bad request");
    }

    /** 请求体缺失或类型不匹配（如缺 body、错误的 Content-Type）：统一按 Go 的 decode 失败处理 */
    @ExceptionHandler({HttpMediaTypeNotSupportedException.class, HttpMessageConversionException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(Exception ex) {
        return error(HttpStatus.BAD_REQUEST, "bad request");
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handleApi(ApiException ex) {
        return error(ex.getStatus(), ex.getMessage());
    }

    /** 未匹配的路径（Spring 6.1 静态资源 404）：保持 JSON 错误格式 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoResourceFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "not found");
    }

    /** 其余未预期异常：500 + 错误信息，与 Go 的 {"error": err.Error()} 一致 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleOther(Exception ex) {
        log.error("未处理异常", ex);
        String message = ex.getMessage();
        return error(HttpStatus.INTERNAL_SERVER_ERROR,
                (message != null && !message.isEmpty()) ? message : "internal error");
    }
}
