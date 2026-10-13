package com.runpulse.server.exception;

import com.runpulse.server.controller.admin.AdminResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 管理后台异常处理：返回 {code,msg} 信封。
 * 优先级高于全局的 GlobalExceptionHandler（后者继续负责 App API 的裸 JSON 错误）。
 */
@RestControllerAdvice(basePackages = "com.runpulse.server.controller.admin")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminExceptionHandler.class);

    private static ResponseEntity<AdminResponse<Void>> body(HttpStatus status, int code, String msg) {
        return ResponseEntity.status(status).body(AdminResponse.error(code, msg));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<AdminResponse<Void>> handleApi(ApiException ex) {
        HttpStatus status = ex.getStatus();
        int code = status.is4xxClientError() ? status.value() : 500;
        return body(status, code, ex.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<AdminResponse<Void>> handleBadRequest(Exception ex) {
        String msg = (ex instanceof MethodArgumentNotValidException valid && valid.getBindingResult().hasErrors())
                ? valid.getBindingResult().getAllErrors().get(0).getDefaultMessage()
                : "请求参数不合法";
        return body(HttpStatus.BAD_REQUEST, 400, msg);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AdminResponse<Void>> handleOther(Exception ex) {
        log.error("管理后台未处理异常", ex);
        String message = ex.getMessage();
        return body(HttpStatus.INTERNAL_SERVER_ERROR, 500,
                (message != null && !message.isEmpty()) ? message : "服务器内部错误");
    }
}
