package com.runpulse.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 管理后台 JWT 鉴权：保护 /admin/api/**（登录接口豁免）。
 * 校验通过后在请求属性中放置 adminUid / adminUsername。
 */
@Component
@Order(1)
public class AdminAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_UID = "adminUid";
    public static final String ATTR_USERNAME = "adminUsername";

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public AdminAuthFilter(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/admin/api/")) {
            return true;
        }
        return "/admin/api/auth/login".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        Claims claims = null;
        if (header != null && header.startsWith("Bearer ")) {
            claims = jwtService.parse(header.substring(7));
        }
        if (claims == null) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(
                    Map.of("code", 401, "msg", "未登录或登录已过期")));
            return;
        }
        request.setAttribute(ATTR_UID, ((Number) claims.get("uid")).longValue());
        request.setAttribute(ATTR_USERNAME, claims.getSubject());
        chain.doFilter(request, response);
    }
}
