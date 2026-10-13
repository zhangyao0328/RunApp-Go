package com.runpulse.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runpulse.server.config.RunpulseProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * X-API-Key 简单鉴权（对应 Go 版本 withAuth）。
 * 未配置 RUNPULSE_API_KEY 时放行；/healthz 公开。
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private final RunpulseProperties props;
    private final ObjectMapper objectMapper;

    public ApiKeyFilter(RunpulseProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // 只保护 App API；管理后台（/admin/**）走 JWT，/healthz 与静态资源公开。
        // 版本检查接口公开，方便 App 启动时检查更新。
        return !uri.startsWith("/api/v1/") || uri.equals("/api/v1/app/version");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String apiKey = props.getApiKey();
        if (!apiKey.isEmpty() && !apiKey.equals(request.getHeader("X-API-Key"))) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(Map.of("error", "invalid api key")));
            return;
        }
        chain.doFilter(request, response);
    }
}
