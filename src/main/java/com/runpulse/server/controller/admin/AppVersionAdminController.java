package com.runpulse.server.controller.admin;

import com.runpulse.server.model.AppVersion;
import com.runpulse.server.repository.AppVersionRepository;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 管理后台：App 版本管理。
 */
@RestController
@RequestMapping("/admin/api/versions")
public class AppVersionAdminController {

    private final AppVersionRepository repository;

    public AppVersionAdminController(AppVersionRepository repository) {
        this.repository = repository;
    }

    /** 分页查询版本列表 */
    @GetMapping
    public AdminResponse<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "10") int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), 100);
        return AdminResponse.ok(Map.of(
                "list", repository.findPage((page - 1) * pageSize, pageSize),
                "total", repository.count()));
    }

    /** 查询单个版本 */
    @GetMapping("/{id}")
    public AdminResponse<AppVersion> get(@PathVariable Long id) {
        return repository.findById(id)
                .map(AdminResponse::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在"));
    }

    /** 创建新版本 */
    @PostMapping
    public AdminResponse<Map<String, Object>> create(@RequestBody AppVersion version) {
        // 检查 versionCode 是否已存在
        if (repository.findByVersionCode(version.versionCode).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "版本号已存在");
        }
        repository.insert(version);
        return AdminResponse.ok(Map.of("id", version.id != null ? version.id : 0));
    }

    /** 更新版本 */
    @PutMapping("/{id}")
    public AdminResponse<Void> update(@PathVariable Long id, @RequestBody AppVersion version) {
        version.id = id;
        if (repository.update(version) == 0) {
            return AdminResponse.error(404, "版本不存在");
        }
        return AdminResponse.ok(null);
    }

    /** 启用/禁用版本 */
    @PutMapping("/{id}/status")
    public AdminResponse<Void> updateStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        Integer status = body.get("status");
        if (status == null || (status != 0 && status != 1)) {
            return AdminResponse.error(400, "status 只能为 0 或 1");
        }
        if (repository.updateStatus(id, status) == 0) {
            return AdminResponse.error(404, "版本不存在");
        }
        return AdminResponse.ok(null);
    }

    /** 删除版本 */
    @DeleteMapping("/{id}")
    public AdminResponse<Void> delete(@PathVariable Long id) {
        if (repository.delete(id) == 0) {
            return AdminResponse.error(404, "版本不存在");
        }
        return AdminResponse.ok(null);
    }
}
