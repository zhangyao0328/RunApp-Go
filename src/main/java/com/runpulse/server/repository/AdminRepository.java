package com.runpulse.server.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理后台持久层：复用 gin-vue-admin 留下的 sys_users / sys_authorities / sys_user_authority 表。
 * 用户软删（deleted_at），查询一律过滤 IS NULL。
 */
@Repository
public class AdminRepository {

    private final JdbcTemplate jdbc;

    public AdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 后台用户（不含密码哈希）。 */
    public record AdminUser(
            long id,
            String uuid,
            String username,
            String nickName,
            String phone,
            String email,
            long enable,
            List<Long> authorityIds) {
    }

    /** 密码校验专用：返回密码哈希。 */
    public record AuthUser(long id, String username, String passwordHash, long enable) {
    }

    public record AdminRole(long authorityId, String authorityName, long parentId) {
    }

    private static final RowMapper<AdminUser> USER_ROW = (rs, n) -> mapUser(rs, List.of());

    private static AdminUser mapUser(ResultSet rs, List<Long> authorityIds) throws SQLException {
        return new AdminUser(
                rs.getLong("id"),
                rs.getString("uuid"),
                rs.getString("username"),
                rs.getString("nick_name"),
                rs.getString("phone"),
                rs.getString("email"),
                rs.getLong("enable"),
                authorityIds);
    }

    public Optional<AuthUser> findForAuth(String username) {
        List<AuthUser> list = jdbc.query("""
                SELECT id, username, password, enable FROM sys_users
                WHERE username = ? AND deleted_at IS NULL
                """, (rs, n) -> new AuthUser(rs.getLong("id"), rs.getString("username"),
                rs.getString("password"), rs.getLong("enable")), username);
        return list.stream().findFirst();
    }

    public Optional<AdminUser> findById(long id) {
        List<AdminUser> list = jdbc.query("""
                SELECT id, uuid, username, nick_name, phone, email, enable
                FROM sys_users WHERE id = ? AND deleted_at IS NULL
                """, USER_ROW, id);
        return list.stream().findFirst().map(u -> new AdminUser(u.id(), u.uuid(), u.username(),
                u.nickName(), u.phone(), u.email(), u.enable(), authorityIdsOf(id)));
    }

    public List<Long> authorityIdsOf(long userId) {
        return jdbc.query("""
                SELECT sys_authority_authority_id FROM sys_user_authority WHERE sys_user_id = ?
                """, (rs, n) -> rs.getLong(1), userId);
    }

    public List<String> authorityNamesOf(long userId) {
        return jdbc.query("""
                SELECT a.authority_name FROM sys_authorities a
                JOIN sys_user_authority ua ON ua.sys_authority_authority_id = a.authority_id
                WHERE ua.sys_user_id = ? AND a.deleted_at IS NULL
                """, (rs, n) -> rs.getString(1), userId);
    }

    public long countUsers(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_users WHERE deleted_at IS NULL", Long.class);
        }
        String like = "%" + keyword + "%";
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_users
                WHERE deleted_at IS NULL AND (username LIKE ? OR nick_name LIKE ? OR phone LIKE ?)
                """, Long.class, like, like, like);
    }

    public List<AdminUser> userPage(int offset, int size, String keyword) {
        String where = (keyword == null || keyword.isEmpty()) ? "" :
                " AND (username LIKE ? OR nick_name LIKE ? OR phone LIKE ?)";
        Object[] params = (keyword == null || keyword.isEmpty())
                ? new Object[]{size, offset}
                : new Object[]{"%" + keyword + "%", "%" + keyword + "%", "%" + keyword + "%", size, offset};
        List<AdminUser> users = jdbc.query("""
                SELECT id, uuid, username, nick_name, phone, email, enable
                FROM sys_users WHERE deleted_at IS NULL%s
                ORDER BY id LIMIT ? OFFSET ?
                """.formatted(where), USER_ROW, params);
        return users.stream()
                .map(u -> new AdminUser(u.id(), u.uuid(), u.username(), u.nickName(), u.phone(),
                        u.email(), u.enable(), authorityIdsOf(u.id())))
                .toList();
    }

    /** 新增用户，返回自增 id。authorityIds 为空时默认 9528（普通用户）。 */
    @Transactional
    public long createUser(String uuid, String username, String passwordHash, String nickName,
                           String phone, String email, List<Long> authorityIds) {
        List<Long> roles = (authorityIds == null || authorityIds.isEmpty())
                ? List.of(9528L) : authorityIds;
        jdbc.update("""
                INSERT INTO sys_users (uuid, username, password, nick_name, phone, email, authority_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, uuid, username, passwordHash, nickName, phone, email, roles.get(0));
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        replaceAuthorities(id, roles);
        return id;
    }

    public boolean usernameExists(String username) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_users WHERE username = ? AND deleted_at IS NULL",
                Long.class, username);
        return n != null && n > 0;
    }

    public void updateUser(long id, String nickName, String phone, String email, Long enable) {
        jdbc.update("""
                UPDATE sys_users SET nick_name = ?, phone = ?, email = ?,
                    enable = COALESCE(?, enable), updated_at = NOW(3)
                WHERE id = ?
                """, nickName, phone, email, enable, id);
    }

    public void updatePassword(long id, String passwordHash) {
        jdbc.update("UPDATE sys_users SET password = ?, updated_at = NOW(3) WHERE id = ?",
                passwordHash, id);
    }

    /** 软删除并清理角色关联。 */
    @Transactional
    public void softDelete(long id) {
        jdbc.update("UPDATE sys_users SET deleted_at = NOW(3) WHERE id = ?", id);
        jdbc.update("DELETE FROM sys_user_authority WHERE sys_user_id = ?", id);
    }

    /** 覆盖式更新用户角色关联，并同步 sys_users.authority_id（主角色，兼容旧数据）。 */
    @Transactional
    public void replaceAuthorities(long userId, List<Long> authorityIds) {
        jdbc.update("DELETE FROM sys_user_authority WHERE sys_user_id = ?", userId);
        for (long roleId : authorityIds) {
            jdbc.update("""
                    INSERT INTO sys_user_authority (sys_user_id, sys_authority_authority_id)
                    VALUES (?, ?)
                    """, userId, roleId);
        }
        if (!authorityIds.isEmpty()) {
            jdbc.update("UPDATE sys_users SET authority_id = ? WHERE id = ?",
                    authorityIds.get(0), userId);
        }
    }

    public List<AdminRole> listRoles() {
        return jdbc.query("""
                SELECT authority_id, authority_name, parent_id FROM sys_authorities
                WHERE deleted_at IS NULL ORDER BY authority_id
                """, (rs, n) -> new AdminRole(rs.getLong("authority_id"),
                rs.getString("authority_name"), rs.getLong("parent_id")));
    }

    public long createRole(String authorityName) {
        jdbc.update("""
                INSERT INTO sys_authorities (authority_name, parent_id) VALUES (?, 0)
                """, authorityName);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void updateRole(long authorityId, String authorityName) {
        jdbc.update("""
                UPDATE sys_authorities SET authority_name = ?, updated_at = NOW(3)
                WHERE authority_id = ?
                """, authorityName, authorityId);
    }

    public long countRoleUsers(long authorityId) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_user_authority ua
                JOIN sys_users u ON u.id = ua.sys_user_id
                WHERE ua.sys_authority_authority_id = ? AND u.deleted_at IS NULL
                """, Long.class, authorityId);
        return n == null ? 0 : n;
    }

    public void softDeleteRole(long authorityId) {
        jdbc.update("UPDATE sys_authorities SET deleted_at = NOW(3) WHERE authority_id = ?",
                authorityId);
        jdbc.update("DELETE FROM sys_user_authority WHERE sys_authority_authority_id = ?",
                authorityId);
    }

    public long countActiveUsers() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_users WHERE deleted_at IS NULL AND enable = 1",
                Long.class);
        return n == null ? 0 : n;
    }
}
