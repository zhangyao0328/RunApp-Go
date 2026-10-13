package com.runpulse.server.controller.admin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.runpulse.server.exception.ApiException;
import com.runpulse.server.repository.AdminRepository;
import com.runpulse.server.security.AdminAuthFilter;
import com.runpulse.server.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台登录与当前用户信息。
 */
@RestController
@RequestMapping("/admin/api/auth")
public class AuthController {

    private final AdminRepository adminRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(AdminRepository adminRepository, BCryptPasswordEncoder passwordEncoder,
                          JwtService jwtService) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoginRequest(@NotBlank(message = "用户名不能为空") String username,
                               @NotBlank(message = "密码不能为空") String password) {
    }

    @PostMapping("/login")
    public AdminResponse<Map<String, Object>> login(@RequestBody LoginRequest req) {
        Optional<AdminRepository.AuthUser> user = adminRepository.findForAuth(req.username());
        if (user.isEmpty() || !passwordEncoder.matches(req.password(), user.get().passwordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.get().enable() != 1) {
            throw new ApiException(HttpStatus.FORBIDDEN, "账号已被禁用");
        }
        String token = jwtService.issue(user.get().id(), user.get().username());
        return AdminResponse.ok(Map.of("token", token));
    }

    /** 当前登录用户信息（过滤器已保证登录态）。 */
    @GetMapping("/info")
    public AdminResponse<Map<String, Object>> info(HttpServletRequest request) {
        long uid = (long) request.getAttribute(AdminAuthFilter.ATTR_UID);
        String username = (String) request.getAttribute(AdminAuthFilter.ATTR_USERNAME);
        List<String> roles = adminRepository.authorityNamesOf(uid);
        return AdminResponse.ok(Map.of(
                "id", uid,
                "username", username,
                "nickName", adminRepository.findById(uid)
                        .map(AdminRepository.AdminUser::nickName).orElse(""),
                "roles", roles));
    }
}
