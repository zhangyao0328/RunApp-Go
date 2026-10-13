package com.runpulse.server.controller.admin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.runpulse.server.exception.ApiException;
import com.runpulse.server.repository.AdminRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台用户管理。
 */
@RestController
@RequestMapping("/admin/api/users")
public class UserController {

    private static final long SUPER_ADMIN_ID = 1L;
    private static final String DEFAULT_PASSWORD = "123456";

    private final AdminRepository adminRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserController(AdminRepository adminRepository, BCryptPasswordEncoder passwordEncoder) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public AdminResponse<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "10") int pageSize,
                                                   @RequestParam(required = false) String keyword) {
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), 100);
        List<AdminRepository.AdminUser> users =
                adminRepository.userPage((page - 1) * pageSize, pageSize, keyword);
        return AdminResponse.ok(Map.of(
                "list", users,
                "total", adminRepository.countUsers(keyword)));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateUserRequest(
            @NotBlank(message = "用户名不能为空") @Size(max = 50, message = "用户名过长") String username,
            String password,
            String nickName,
            String phone,
            String email,
            List<Long> authorityIds) {
    }

    @PostMapping
    public AdminResponse<Map<String, Object>> create(@Valid @RequestBody CreateUserRequest req) {
        if (adminRepository.usernameExists(req.username())) {
            throw new ApiException(HttpStatus.CONFLICT, "用户名已存在");
        }
        String rawPassword = (req.password() == null || req.password().isEmpty())
                ? DEFAULT_PASSWORD : req.password();
        if (rawPassword.length() < 6) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "密码至少 6 位");
        }
        long id = adminRepository.createUser(
                UUID.randomUUID().toString().replace("-", ""),
                req.username(),
                passwordEncoder.encode(rawPassword),
                (req.nickName() == null || req.nickName().isEmpty()) ? req.username() : req.nickName(),
                req.phone(), req.email(),
                req.authorityIds());
        return AdminResponse.ok(Map.of("id", id));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdateUserRequest(String nickName, String phone, String email, Long enable) {
    }

    @PutMapping("/{id}")
    public AdminResponse<Void> update(@PathVariable long id, @RequestBody UpdateUserRequest req) {
        ensureExists(id);
        if (id == SUPER_ADMIN_ID && req.enable() != null && req.enable() != 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "不能禁用超级管理员");
        }
        adminRepository.updateUser(id, req.nickName(), req.phone(), req.email(), req.enable());
        return AdminResponse.ok(null);
    }

    public record ResetPasswordRequest(@NotBlank(message = "密码不能为空") String password) {
    }

    @PutMapping("/{id}/password")
    public AdminResponse<Void> resetPassword(@PathVariable long id,
                                             @Valid @RequestBody ResetPasswordRequest req) {
        ensureExists(id);
        if (req.password().length() < 6) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "密码至少 6 位");
        }
        adminRepository.updatePassword(id, passwordEncoder.encode(req.password()));
        return AdminResponse.ok(null);
    }

    public record AssignRolesRequest(List<Long> authorityIds) {
    }

    @PutMapping("/{id}/roles")
    public AdminResponse<Void> assignRoles(@PathVariable long id,
                                           @RequestBody AssignRolesRequest req) {
        ensureExists(id);
        List<Long> roles = req.authorityIds() == null ? List.of() : req.authorityIds();
        if (id == SUPER_ADMIN_ID && !roles.contains(888L)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "超级管理员必须保留超级管理员角色");
        }
        adminRepository.replaceAuthorities(id, roles);
        return AdminResponse.ok(null);
    }

    @DeleteMapping("/{id}")
    public AdminResponse<Void> delete(@PathVariable long id) {
        ensureExists(id);
        if (id == SUPER_ADMIN_ID) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "不能删除超级管理员");
        }
        adminRepository.softDelete(id);
        return AdminResponse.ok(null);
    }

    private void ensureExists(long id) {
        if (adminRepository.findById(id).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "用户不存在");
        }
    }
}
