package com.runpulse.server.controller.admin;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 管理后台统一响应信封：成功 {code:0,data}，失败 {code,msg}。
 * 与 App API（/api/v1/*，裸 JSON）格式完全隔离。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminResponse<T>(int code, String msg, T data) {

    public static <T> AdminResponse<T> ok(T data) {
        return new AdminResponse<>(0, null, data);
    }

    public static AdminResponse<Void> error(int code, String msg) {
        return new AdminResponse<>(code, msg, null);
    }
}
