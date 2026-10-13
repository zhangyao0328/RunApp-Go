package com.runpulse.server.repository;

import com.runpulse.server.model.AnalysisMessage;
import com.runpulse.server.model.ClientEvent;
import com.runpulse.server.model.TrackPoint;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 持久化层，对应 Go 版本 store.go。JdbcTemplate 精确复刻原 SQL、事务与幂等语义。
 */
@Repository
public class RunPulseRepository {

    private final JdbcTemplate jdbc;

    public RunPulseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等：App 可能因进程被杀重启后重复上报 start。userId 为运动用户归属，可为 null（老客户端/管理端导入）。 */
    public void upsertSessionStart(String sessionId, String sportType, long startTime, Long userId) {
        jdbc.update("""
                INSERT INTO rp_sessions (session_id, sport_type, start_time, user_id)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE session_id = session_id
                """, sessionId, sportType, startTime, userId);
    }

    public void endSession(String sessionId, long endTime) {
        jdbc.update("UPDATE rp_sessions SET end_time = ? WHERE session_id = ?", endTime, sessionId);
    }

    /**
     * 批量插入轨迹点（单事务），返回最后一个点的自增 ID（对应 Go InsertPoints）。
     * 使用 INSERT IGNORE 保证幂等：rp_track_points 有 uk_session_ts(session_id, timestamp) 唯一键，
     * 重传/并发重试产生的重复点会被静默忽略（jdbc.update 返回 0，不取自增主键）。
     */
    @Transactional
    public long insertPoints(String sessionId, List<TrackPoint> points) {
        String sql = """
                INSERT IGNORE INTO rp_track_points (session_id, lat, lng, altitude, speed, accuracy, heart_rate, cadence, timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        long lastId = 0;
        for (TrackPoint p : points) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            int rowsUpdated = jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, sessionId);
                ps.setDouble(2, p.lat);
                ps.setDouble(3, p.lng);
                if (p.altitude != null) {
                    ps.setDouble(4, p.altitude);
                } else {
                    ps.setObject(4, null);
                }
                if (p.speed != null) {
                    ps.setDouble(5, p.speed);
                } else {
                    ps.setObject(5, null);
                }
                if (p.accuracy != null) {
                    ps.setDouble(6, p.accuracy);
                } else {
                    ps.setObject(6, null);
                }
                if (p.heartRate != null) {
                    ps.setInt(7, p.heartRate);
                } else {
                    ps.setObject(7, null);
                }
                if (p.cadence != null) {
                    ps.setInt(8, p.cadence);
                } else {
                    ps.setObject(8, null);
                }
                ps.setLong(9, p.timestamp);
                return ps;
            }, keyHolder);
            Number key = rowsUpdated > 0 ? keyHolder.getKey() : null;
            if (key != null) {
                lastId = key.longValue();
            }
        }
        return lastId;
    }

    /**
     * 判断该 session 是否攒够了触发一次 AI 分析的数据量（对应 Go PendingAnalysis）。
     * session 不存在视为不触发；已结束的 session 不再做实时分析。
     */
    public boolean pendingAnalysis(String sessionId, int minPoints, long minIntervalSec) {
        List<SessionProgress> rows = jdbc.query(
                "SELECT last_analyzed_point_id, last_analysis_at, end_time FROM rp_sessions WHERE session_id = ?",
                (rs, n) -> new SessionProgress(
                        rs.getLong("last_analyzed_point_id"),
                        rs.getLong("last_analysis_at"),
                        toLong(rs.getObject("end_time"))),
                sessionId);
        if (rows.isEmpty()) {
            return false;
        }
        SessionProgress sp = rows.get(0);
        if (sp.endTime != null) {
            return false; // 已结束的 session 不再做实时分析（后续可加"训练后报告"）
        }
        long intervalMs = minIntervalSec * 1000;
        if (sp.lastAnalysisAt != 0 && System.currentTimeMillis() - sp.lastAnalysisAt < intervalMs) {
            return false;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rp_track_points WHERE session_id = ? AND id > ?",
                Integer.class, sessionId, sp.lastAnalyzedPointId);
        return count != null && count >= minPoints;
    }

    /** 取 session 全部轨迹点（分析用，1.0 数据量小直接全取）。 */
    public List<TrackPoint> sessionPoints(String sessionId) {
        return jdbc.query("""
                SELECT id, lat, lng, altitude, speed, accuracy, heart_rate, cadence, timestamp
                FROM rp_track_points WHERE session_id = ? ORDER BY id
                """, (rs, n) -> {
            TrackPoint p = new TrackPoint();
            p.id = rs.getLong("id");
            p.sessionId = sessionId;
            p.lat = rs.getDouble("lat");
            p.lng = rs.getDouble("lng");
            p.altitude = toDouble(rs.getObject("altitude"));
            p.speed = toDouble(rs.getObject("speed"));
            p.accuracy = toDouble(rs.getObject("accuracy"));
            p.heartRate = toInt(rs.getObject("heart_rate"));
            p.cadence = toInt(rs.getObject("cadence"));
            p.timestamp = rs.getLong("timestamp");
            return p;
        }, sessionId);
    }

    // 生产库部分列实际为 BIGINT/DOUBLE（老版本迁移遗留），getObject 返回 Long/BigDecimal 等，
    // 不能用 (Integer)/(Double) 强转，统一走 Number 转换。
    private static Integer toInt(Object v) { return v == null ? null : ((Number) v).intValue(); }
    private static Long toLong(Object v) { return v == null ? null : ((Number) v).longValue(); }
    private static Double toDouble(Object v) { return v == null ? null : ((Number) v).doubleValue(); }

    /** 查询 session 的运动类型（AI 提示词用）。 */
    public String findSportType(String sessionId) {
        List<String> rows = jdbc.query(
                "SELECT sport_type FROM rp_sessions WHERE session_id = ?",
                (rs, n) -> rs.getString("sport_type"), sessionId);
        return rows.isEmpty() ? "" : rows.get(0);
    }

    /** 写入一条分析结果，并推进分析进度游标（单事务，对应 Go SaveAnalysis）。 */
    @Transactional
    public void saveAnalysis(String sessionId, String text, long lastPointId) {
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO rp_coach_messages (session_id, timestamp, text) VALUES (?, ?, ?)",
                sessionId, now, text);
        jdbc.update("""
                UPDATE rp_sessions SET last_analyzed_point_id = ?, last_analysis_at = ? WHERE session_id = ?
                """, lastPointId, now, sessionId);
    }

    /** 增量拉取分析消息，App 端用 since_id 轮询。无结果返回空列表（保证 messages 永不为 null）。 */
    public List<AnalysisMessage> analysisSince(String sessionId, long sinceId) {
        return jdbc.query("""
                SELECT id, timestamp, text FROM rp_coach_messages
                WHERE session_id = ? AND id > ? ORDER BY id
                """, (rs, n) -> new AnalysisMessage(
                rs.getLong("id"), rs.getLong("timestamp"), rs.getString("text")),
                sessionId, sinceId);
    }

    /** 批量写入诊断事件，server_ts 由服务端盖戳（客户端时间可能不准）。 */
    @Transactional
    public void insertEvents(List<ClientEvent> events) {
        String sql = """
                INSERT INTO rp_client_events (session_id, event, detail, client_ts, server_ts) VALUES (?, ?, ?, ?, ?)
                """;
        long now = System.currentTimeMillis();
        for (ClientEvent e : events) {
            jdbc.update(sql, e.sessionId, e.event, e.detail, e.clientTs, now);
        }
    }

    private record SessionProgress(long lastAnalyzedPointId, long lastAnalysisAt, Long endTime) {
    }

    // ==================== 运动用户（rp_app_users，设备 ID 匿名建档） ====================

    /** 运动用户下拉行（会话筛选等场景）。 */
    public record AppUser(long id, String deviceId, String nickname) {
    }

    /**
     * 按 device_id 查找运动用户，不存在则建档（昵称自动生成），并刷新 last_seen。
     * 返回用户 id。deviceId 为空时由调用方提前拦截，这里不处理 null。
     */
    @Transactional
    public long findOrCreateAppUser(String deviceId, long now) {
        List<Long> existing = jdbc.query("SELECT id FROM rp_app_users WHERE device_id = ?",
                (rs, n) -> rs.getLong(1), deviceId);
        if (!existing.isEmpty()) {
            jdbc.update("UPDATE rp_app_users SET last_seen = ? WHERE id = ?", now, existing.get(0));
            return existing.get(0);
        }
        String nickname = "跑者#" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 6).toUpperCase(Locale.ROOT);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO rp_app_users (device_id, nickname, first_seen, last_seen, created_at)
                        VALUES (?, ?, ?, ?, NOW())
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, deviceId);
                ps.setString(2, nickname);
                ps.setLong(3, now);
                ps.setLong(4, now);
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException e) {
            // 并发重复建档：唯一键冲突后重新查询即可
            jdbc.update("UPDATE rp_app_users SET last_seen = ? WHERE device_id = ?", now, deviceId);
            return jdbc.queryForObject("SELECT id FROM rp_app_users WHERE device_id = ?", Long.class, deviceId);
        }
        return keyHolder.getKey().longValue();
    }

    /** 全部运动用户（按最近活跃倒序），供管理端筛选下拉。 */
    public List<AppUser> listAppUsers() {
        return jdbc.query("""
                SELECT id, device_id, nickname FROM rp_app_users
                ORDER BY last_seen DESC LIMIT 500
                """, (rs, n) -> new AppUser(rs.getLong("id"), rs.getString("device_id"),
                rs.getString("nickname")));
    }

    // ==================== 管理后台查询（/admin/api） ====================

    /** 管理后台列表行：会话基本信息 + 归属用户昵称 + 轨迹点数 + 点评数。 */
    public record SessionRow(
            String sessionId,
            String sportType,
            long startTime,
            Long endTime,
            long pointCount,
            long messageCount,
            String nickname) {
    }

    private static final RowMapper<SessionRow> SESSION_ROW = (rs, n) -> new SessionRow(
            rs.getString("session_id"),
            rs.getString("sport_type"),
            rs.getLong("start_time"),
            toLong(rs.getObject("end_time")),
            rs.getLong("point_count"),
            rs.getLong("message_count"),
            rs.getString("nickname"));

    private static final String SESSION_PAGE_FROM = """
            SELECT s.session_id, s.sport_type, s.start_time, s.end_time, u.nickname,
                   (SELECT COUNT(*) FROM rp_track_points p WHERE p.session_id = s.session_id) AS point_count,
                   (SELECT COUNT(*) FROM rp_coach_messages m WHERE m.session_id = s.session_id) AS message_count
            FROM rp_sessions s
            LEFT JOIN rp_app_users u ON u.id = s.user_id
            """;

    /**
     * 会话分页。userId：null = 全部；0 = 未归属（user_id IS NULL）；&gt;0 = 指定用户。
     * 用动态 SQL 拼接，避免 NULL 参数绑定在 (? IS NULL OR ...) 里语义含混。
     */
    public List<SessionRow> sessionPage(int offset, int size, String sportType, Long userId) {
        StringBuilder sql = new StringBuilder(SESSION_PAGE_FROM)
                .append(" WHERE (? IS NULL OR s.sport_type = ?)");
        List<Object> args = new ArrayList<>();
        args.add(sportType);
        args.add(sportType);
        if (userId != null) {
            if (userId == 0) {
                sql.append(" AND s.user_id IS NULL");
            } else {
                sql.append(" AND s.user_id = ?");
                args.add(userId);
            }
        }
        sql.append(" ORDER BY s.start_time DESC LIMIT ? OFFSET ?");
        args.add(size);
        args.add(offset);
        return jdbc.query(sql.toString(), SESSION_ROW, args.toArray());
    }

    public long countSessions(String sportType, Long userId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM rp_sessions s")
                .append(" WHERE (? IS NULL OR s.sport_type = ?)");
        List<Object> args = new ArrayList<>();
        args.add(sportType);
        args.add(sportType);
        if (userId != null) {
            if (userId == 0) {
                sql.append(" AND s.user_id IS NULL");
            } else {
                sql.append(" AND s.user_id = ?");
                args.add(userId);
            }
        }
        Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    public Optional<SessionRow> findSessionRow(String sessionId) {
        List<SessionRow> rows = jdbc.query(SESSION_PAGE_FROM + " WHERE s.session_id = ?",
                SESSION_ROW, sessionId);
        return rows.stream().findFirst();
    }

    public long countPoints() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM rp_track_points", Long.class);
        return n == null ? 0 : n;
    }

    public long countMessages() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM rp_coach_messages", Long.class);
        return n == null ? 0 : n;
    }

    public long countSessionsSince(long startTimeMillis) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rp_sessions WHERE start_time >= ?", Long.class, startTimeMillis);
        return n == null ? 0 : n;
    }

    /** 运动类型分布（管理后台仪表盘）。 */
    public List<SportCount> sportTypeCounts() {
        return jdbc.query("""
                SELECT sport_type, COUNT(*) AS cnt FROM rp_sessions GROUP BY sport_type ORDER BY cnt DESC
                """, (rs, n) -> new SportCount(rs.getString(1), rs.getLong(2)));
    }

    /** 近 N 天内每天的新增会话数（start_time 按天分组）。 */
    public List<DayCount> sessionCountsPerDay(long sinceMillis) {
        return jdbc.query("""
                SELECT FROM_UNIXTIME(start_time / 1000, '%Y-%m-%d') AS day, COUNT(*) AS cnt
                FROM rp_sessions WHERE start_time >= ?
                GROUP BY day ORDER BY day
                """, (rs, n) -> new DayCount(rs.getString(1), rs.getLong(2)), sinceMillis);
    }

    public record SportCount(String sportType, long count) {
    }

    public record DayCount(String day, long count) {
    }
}
