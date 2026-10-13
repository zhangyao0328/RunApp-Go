package com.runpulse.server.config;

import org.apache.catalina.connector.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 管理后台装配：第二 Tomcat 连接器（ADMIN_ADDR）+ BCrypt 密码编码器。
 * 管理端口与主端口（RUNPULSE_ADDR）相同时不额外开 connector（同一端口同时服务 /api/v1 和 /admin）。
 */
@Configuration
public class AdminServerConfig {

    private static final Logger log = LoggerFactory.getLogger(AdminServerConfig.class);

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> adminConnectorCustomizer(
            RunpulseProperties props, AdminProperties admin) {
        return factory -> {
            int mainPort = AdminProperties.parsePort(props.getAddr(), 8080);
            int adminPort = AdminProperties.parsePort(admin.getAddr(), 8080);
            if (adminPort == mainPort) {
                log.info("管理后台与 App API 共用端口 {}（/admin 路径）", mainPort);
                return;
            }
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            String host = AdminProperties.parseHost(admin.getAddr());
            if (host != null) {
                connector.setProperty("address", host);
            }
            connector.setPort(adminPort);
            factory.addAdditionalTomcatConnectors(connector);
            log.info("管理后台监听端口 {}（App API: {}）", adminPort, mainPort);
        };
    }
}
