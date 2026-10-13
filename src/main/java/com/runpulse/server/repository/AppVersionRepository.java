package com.runpulse.server.repository;

import com.runpulse.server.model.AppVersion;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * App 版本管理数据访问层。
 */
@Repository
public class AppVersionRepository {

    private final JdbcTemplate jdbc;

    public AppVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AppVersion> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    private static AppVersion mapRow(ResultSet rs) throws SQLException {
        AppVersion v = new AppVersion();
        v.id = rs.getLong("id");
        v.versionCode = rs.getInt("version_code");
        v.versionName = rs.getString("version_name");
        v.apkUrl = rs.getString("apk_url");
        v.apkSize = rs.getLong("apk_size");
        v.apkMd5 = rs.getString("apk_md5");
        v.updateLog = rs.getString("update_log");
        v.forceUpdate = rs.getBoolean("force_update");
        v.status = rs.getInt("status");
        v.createdAt = rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null;
        v.updatedAt = rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null;
        return v;
    }

    /** 查询最新启用的版本（App 检查更新用） */
    public Optional<AppVersion> findLatestEnabled() {
        List<AppVersion> list = jdbc.query(
                "SELECT * FROM rp_app_version WHERE status = 1 ORDER BY version_code DESC LIMIT 1",
                ROW_MAPPER);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** 分页查询版本列表（管理后台用） */
    public List<AppVersion> findPage(int offset, int pageSize) {
        return jdbc.query(
                "SELECT * FROM rp_app_version ORDER BY version_code DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, pageSize, offset);
    }

    /** 统计总数 */
    public int count() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM rp_app_version", Integer.class);
        return count != null ? count : 0;
    }

    /** 根据 ID 查询 */
    public Optional<AppVersion> findById(Long id) {
        List<AppVersion> list = jdbc.query(
                "SELECT * FROM rp_app_version WHERE id = ?",
                ROW_MAPPER, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** 根据 versionCode 查询 */
    public Optional<AppVersion> findByVersionCode(Integer versionCode) {
        List<AppVersion> list = jdbc.query(
                "SELECT * FROM rp_app_version WHERE version_code = ?",
                ROW_MAPPER, versionCode);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** 插入新版本 */
    public int insert(AppVersion v) {
        return jdbc.update("""
                INSERT INTO rp_app_version (version_code, version_name, apk_url, apk_size, apk_md5, update_log, force_update, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                v.versionCode, v.versionName, v.apkUrl, v.apkSize, v.apkMd5, v.updateLog,
                v.forceUpdate != null && v.forceUpdate ? 1 : 0,
                v.status != null ? v.status : 1);
    }

    /** 更新版本 */
    public int update(AppVersion v) {
        return jdbc.update("""
                UPDATE rp_app_version SET version_name = ?, apk_url = ?, apk_size = ?, apk_md5 = ?,
                update_log = ?, force_update = ?, status = ? WHERE id = ?
                """,
                v.versionName, v.apkUrl, v.apkSize, v.apkMd5, v.updateLog,
                v.forceUpdate != null && v.forceUpdate ? 1 : 0,
                v.status != null ? v.status : 1,
                v.id);
    }

    /** 更新状态（启用/禁用） */
    public int updateStatus(Long id, Integer status) {
        return jdbc.update("UPDATE rp_app_version SET status = ? WHERE id = ?", status, id);
    }

    /** 删除版本 */
    public int delete(Long id) {
        return jdbc.update("DELETE FROM rp_app_version WHERE id = ?", id);
    }
}
