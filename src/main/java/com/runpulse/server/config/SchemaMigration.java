package com.runpulse.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 幂等结构迁移：schema.sql 只 CREATE TABLE IF NOT EXISTS，无法给已存在的表补列，
 * 这里在启动时检查 information_schema 并补建（兼容老库由 Go 版本迁移来的表结构）。
 */
@Component
public class SchemaMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigration.class);

    private final JdbcTemplate jdbc;

    public SchemaMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureSessionUserIdColumn();
        ensureAppVersionTable();
    }

    private void ensureSessionUserIdColumn() {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rp_sessions' AND COLUMN_NAME = 'user_id'
                """, Integer.class);
        if (n != null && n == 0) {
            jdbc.execute("""
                    ALTER TABLE rp_sessions
                    ADD COLUMN user_id BIGINT UNSIGNED NULL,
                    ADD INDEX idx_user_id (user_id)
                    """);
            log.info("schema migration: rp_sessions.user_id 列已补建");
        }
    }

    /** 创建 App 版本管理表 */
    private void ensureAppVersionTable() {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rp_app_version'
                """, Integer.class);
        if (n != null && n == 0) {
            jdbc.execute("""
                    CREATE TABLE rp_app_version (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        version_code INT NOT NULL COMMENT '版本号（如 1, 2, 3）',
                        version_name VARCHAR(32) NOT NULL COMMENT '版本名（如 1.0.0）',
                        apk_url VARCHAR(512) NOT NULL COMMENT 'APK 下载地址',
                        apk_size BIGINT COMMENT 'APK 文件大小（字节）',
                        apk_md5 VARCHAR(64) COMMENT 'APK MD5 校验值',
                        update_log TEXT COMMENT '更新日志',
                        force_update TINYINT(1) DEFAULT 0 COMMENT '是否强制更新',
                        status TINYINT DEFAULT 1 COMMENT '状态：1-启用，0-禁用',
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        UNIQUE KEY uk_version_code (version_code)
                    ) COMMENT='App 版本管理表'
                    """);
            log.info("schema migration: rp_app_version 表已创建");
        }
    }
}
