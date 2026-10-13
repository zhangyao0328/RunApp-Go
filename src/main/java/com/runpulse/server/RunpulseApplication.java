package com.runpulse.server;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RunpulseApplication {

    private static final Logger log = LoggerFactory.getLogger(RunpulseApplication.class);

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(RunpulseApplication.class);

        // 兼容 Go 版本的 RUNPULSE_ADDR（如 :8080 / :18080 / 127.0.0.1:8080）。
        // 使用 defaultProperties 保持低优先级，显式 --server.port / SERVER_PORT 仍可覆盖。
        Map<String, Object> defaults = new HashMap<>();
        String addr = System.getenv().getOrDefault("RUNPULSE_ADDR", ":8080");
        int idx = addr.lastIndexOf(':');
        if (idx >= 0) {
            String host = addr.substring(0, idx);
            String port = addr.substring(idx + 1);
            if (!host.isEmpty()) {
                defaults.put("server.address", host);
            }
            if (!port.isEmpty()) {
                defaults.put("server.port", port);
            }
        } else {
            log.warn("RUNPULSE_ADDR 格式无法解析（{}），使用默认端口 8080", addr);
        }
        app.setDefaultProperties(defaults);
        app.run(args);
    }
}
