package com.runpulse.server.controller.admin;

import com.runpulse.server.repository.RunPulseRepository;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台：运动用户（rp_app_users，App 设备 ID 匿名建档，区别于 sys_users 管理账号）。
 */
@RestController
@RequestMapping("/admin/api/app-users")
public class AppUserAdminController {

    private final RunPulseRepository repository;

    public AppUserAdminController(RunPulseRepository repository) {
        this.repository = repository;
    }

    /** 全部运动用户（最近活跃倒序，前 500），供会话筛选等下拉场景。 */
    @GetMapping
    public AdminResponse<List<RunPulseRepository.AppUser>> list() {
        return AdminResponse.ok(repository.listAppUsers());
    }
}
