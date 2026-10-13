package com.runpulse.server.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.LocalDateTime;

/**
 * App 版本信息。对应 rp_app_version 表。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AppVersion {

    public Long id;
    public Integer versionCode;
    public String versionName;
    public String apkUrl;
    public Long apkSize;
    public String apkMd5;
    public String updateLog;
    public Boolean forceUpdate;
    public Integer status; // 1-启用，0-禁用
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
