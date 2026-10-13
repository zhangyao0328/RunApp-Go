package com.runpulse.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 全部配置通过环境变量读取，与 Go 版本 config.go 的 LoadConfig() 一一对应。
 */
@Component
public class RunpulseProperties {

    private static final Logger log = LoggerFactory.getLogger(RunpulseProperties.class);

    private final String addr;
    private final String apiKey;

    private final String dbHost;
    private final String dbPort;
    private final String dbUser;
    private final String dbPassword;
    private final String dbName;

    private final String openclawBaseUrl;
    private final String openclawApiKey;
    private final String openclawModel;

    private final int analysisMinPoints;
    private final long analysisMinIntervalSec;

    public RunpulseProperties(Environment env) {
        this.addr = getEnv(env, "RUNPULSE_ADDR", ":8080");
        this.apiKey = getEnv(env, "RUNPULSE_API_KEY", "");
        this.dbHost = getEnv(env, "RUNPULSE_DB_HOST", "127.0.0.1");
        this.dbPort = getEnv(env, "RUNPULSE_DB_PORT", "3306");
        this.dbUser = getEnv(env, "RUNPULSE_DB_USER", "root");
        this.dbPassword = getEnv(env, "RUNPULSE_DB_PASSWORD", "");
        this.dbName = getEnv(env, "RUNPULSE_DB_NAME", "gva");
        this.openclawBaseUrl = getEnv(env, "OPENCLAW_BASE_URL", "");
        this.openclawApiKey = getEnv(env, "OPENCLAW_API_KEY", "");
        this.openclawModel = getEnv(env, "OPENCLAW_MODEL", "");
        this.analysisMinPoints = getEnvInt(env, "ANALYSIS_MIN_POINTS", 30);
        this.analysisMinIntervalSec = getEnvInt(env, "ANALYSIS_MIN_INTERVAL_SEC", 180);

        if (this.apiKey.isEmpty()) {
            log.warn("[warn] RUNPULSE_API_KEY 未设置，接口将不校验身份（仅建议本地调试）");
        }
        if (this.openclawBaseUrl.isEmpty() || this.openclawModel.isEmpty()) {
            log.warn("[warn] OPENCLAW_BASE_URL / OPENCLAW_MODEL 未配置，AI 分析功能不可用，仅做数据采集存储");
        }
    }

    private static String getEnv(Environment env, String key, String def) {
        String v = env.getProperty(key);
        return (v != null && !v.isEmpty()) ? v : def;
    }

    private static int getEnvInt(Environment env, String key, int def) {
        String v = env.getProperty(key);
        if (v != null && !v.isEmpty()) {
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return def;
    }

    public String getAddr() {
        return addr;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getDbHost() {
        return dbHost;
    }

    public String getDbPort() {
        return dbPort;
    }

    public String getDbUser() {
        return dbUser;
    }

    public String getDbPassword() {
        return dbPassword;
    }

    public String getDbName() {
        return dbName;
    }

    public String getOpenclawBaseUrl() {
        return openclawBaseUrl;
    }

    public String getOpenclawApiKey() {
        return openclawApiKey;
    }

    public String getOpenclawModel() {
        return openclawModel;
    }

    public int getAnalysisMinPoints() {
        return analysisMinPoints;
    }

    public long getAnalysisMinIntervalSec() {
        return analysisMinIntervalSec;
    }
}
