package com.runpulse.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 管理后台配置：ADMIN_ADDR（管理端口，默认 :8080）、ADMIN_JWT_SECRET。
 */
@Component
public class AdminProperties {

    private static final Logger log = LoggerFactory.getLogger(AdminProperties.class);

    /** 未配置密钥时的本地开发默认值，生产必须在 systemd unit 里配置 ADMIN_JWT_SECRET。 */
    private static final String DEV_SECRET = "runpulse-admin-dev-secret-0123456789abcdef";

    private final String addr;
    private final String jwtSecret;
    private final long tokenTtlMillis;

    public AdminProperties(Environment env) {
        this.addr = getEnv(env, "ADMIN_ADDR", ":8080");
        String secret = env.getProperty("ADMIN_JWT_SECRET");
        if (secret == null || secret.isEmpty()) {
            this.jwtSecret = DEV_SECRET;
            log.warn("[warn] ADMIN_JWT_SECRET 未设置，使用开发默认密钥（生产环境必须配置！）");
        } else {
            this.jwtSecret = secret;
        }
        this.tokenTtlMillis = 12L * 60 * 60 * 1000; // 12 小时
    }

    private static String getEnv(Environment env, String key, String def) {
        String v = env.getProperty(key);
        return (v != null && !v.isEmpty()) ? v : def;
    }

    /** 解析 ":8081" / "127.0.0.1:8081" 中的端口，解析失败返回 defaultPort。 */
    public static int parsePort(String addr, int defaultPort) {
        if (addr == null) {
            return defaultPort;
        }
        int idx = addr.lastIndexOf(':');
        if (idx < 0) {
            return defaultPort;
        }
        try {
            return Integer.parseInt(addr.substring(idx + 1).trim());
        } catch (NumberFormatException e) {
            return defaultPort;
        }
    }

    /** 解析 ":8081" 返回 null（全部网卡），"127.0.0.1:8081" 返回 "127.0.0.1"。 */
    public static String parseHost(String addr) {
        if (addr == null) {
            return null;
        }
        int idx = addr.lastIndexOf(':');
        if (idx <= 0) {
            return null;
        }
        return addr.substring(0, idx);
    }

    public String getAddr() {
        return addr;
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public long getTokenTtlMillis() {
        return tokenTtlMillis;
    }
}
