package com.runpulse.server.controller;

import com.runpulse.server.model.AppVersion;
import com.runpulse.server.repository.AppVersionRepository;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * App 版本检查接口（无需鉴权，公开访问）。
 */
@RestController
@RequestMapping("/api/v1/app")
public class AppVersionController {

    private final AppVersionRepository repository;

    public AppVersionController(AppVersionRepository repository) {
        this.repository = repository;
    }

    /**
     * 检查最新版本。
     *
     * @param currentVersionCode 当前 App 版本号
     * @return 最新版本信息，如果已是最新则 hasUpdate=false
     */
    @GetMapping("/version")
    public ResponseEntity<Map<String, Object>> checkVersion(
            @RequestParam(defaultValue = "0") int currentVersionCode) {

        return repository.findLatestEnabled()
                .map(latest -> {
                    boolean hasUpdate = latest.versionCode > currentVersionCode;
                    Map<String, Object> result = new HashMap<>();
                    result.put("hasUpdate", hasUpdate);
                    result.put("versionCode", latest.versionCode);
                    result.put("versionName", latest.versionName);
                    result.put("apkUrl", latest.apkUrl);
                    result.put("apkSize", latest.apkSize);
                    result.put("apkMd5", latest.apkMd5);
                    result.put("updateLog", latest.updateLog);
                    result.put("forceUpdate", latest.forceUpdate);
                    return ResponseEntity.ok(result);
                })
                .orElse(ResponseEntity.ok(Map.of("hasUpdate", false)));
    }
}
