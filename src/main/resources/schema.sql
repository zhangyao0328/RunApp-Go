-- RunPulse 四张业务表 DDL，与 Go 版本 store.go 中的 migrate() 完全一致（幂等）。
-- 仅执行 CREATE TABLE IF NOT EXISTS，不会重建或修改已有表中的业务数据。

CREATE TABLE IF NOT EXISTS rp_sessions (
    session_id             VARCHAR(36) PRIMARY KEY,
    sport_type             VARCHAR(20) NOT NULL,
    start_time             BIGINT NOT NULL,
    end_time               BIGINT,
    last_analyzed_point_id BIGINT NOT NULL DEFAULT 0,
    last_analysis_at       BIGINT NOT NULL DEFAULT 0,
    sys_user_id            INT UNSIGNED,
    created_at             DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_sys_user_id (sys_user_id),
    INDEX idx_start_time (start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rp_track_points (
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(36) NOT NULL,
    lat        DOUBLE NOT NULL,
    lng        DOUBLE NOT NULL,
    altitude   DOUBLE,
    speed      DOUBLE,
    accuracy   DOUBLE,
    heart_rate INT,
    cadence    INT,
    timestamp  BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_session_ts (session_id, timestamp),
    INDEX idx_session_point (session_id, id),
    INDEX idx_timestamp (timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rp_coach_messages (
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(36) NOT NULL,
    timestamp  BIGINT NOT NULL,
    text       TEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_msg (session_id, id),
    INDEX idx_timestamp (timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rp_client_events (
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(36),
    event      VARCHAR(100) NOT NULL,
    detail     VARCHAR(1024) NOT NULL DEFAULT '',
    client_ts  BIGINT NOT NULL DEFAULT 0,
    server_ts  BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_event (session_id, id),
    INDEX idx_server_ts (server_ts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 运动用户（App 设备 ID 匿名建档，与 sys_users 管理账号完全隔离）。
-- 注意：rp_sessions.user_id 列由 SchemaMigration 在启动时幂等补建（ALTER 无法放进 IF NOT EXISTS）。
CREATE TABLE IF NOT EXISTS rp_app_users (
    id          BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
    device_id   VARCHAR(64) NOT NULL,
    nickname    VARCHAR(64) NOT NULL,
    status      TINYINT     NOT NULL DEFAULT 0,
    first_seen  BIGINT      NOT NULL,
    last_seen   BIGINT      NOT NULL,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_device_id (device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
