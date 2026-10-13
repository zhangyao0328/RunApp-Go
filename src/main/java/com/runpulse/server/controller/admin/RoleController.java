package com.runpulse.server.controller.admin;

import com.runpulse.server.exception.ApiException;
import com.runpulse.server.repository.AdminRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台角色（sys_authorities）管理。
 */
@RestController
@RequestMapping("/admin/api/roles")
public class RoleController {

    private final AdminRepository adminRepository;

    public RoleController(AdminRepository adminRepository) {
        this.adminRepository = adminRepository;
    }

    @GetMapping
    public AdminResponse<Object> list() {
        return AdminResponse.ok(adminRepository.listRoles());
    }

    public record RoleRequest(@NotBlank(message = "角色名不能为空") String authorityName) {
    }

    @PostMapping
    public AdminResponse<Map<String, Object>> create(@Valid @RequestBody RoleRequest req) {
        long id = adminRepository.createRole(req.authorityName());
        return AdminResponse.ok(Map.of("authorityId", id));
    }

    @PutMapping("/{id}")
    public AdminResponse<Void> update(@PathVariable long id, @Valid @RequestBody RoleRequest req) {
        if (id == 888L) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "内置超级管理员角色不可改名");
        }
        adminRepository.updateRole(id, req.authorityName());
        return AdminResponse.ok(null);
    }

    @DeleteMapping("/{id}")
    public AdminResponse<Void> delete(@PathVariable long id) {
        if (id == 888L || id == 9528L) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "内置角色不可删除");
        }
        long users = adminRepository.countRoleUsers(id);
        if (users > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "该角色下仍有 " + users + " 个用户，无法删除");
        }
        adminRepository.softDeleteRole(id);
        return AdminResponse.ok(null);
    }
}
