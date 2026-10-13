package com.runpulse.server.service;

import com.runpulse.server.model.TrackPoint;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * 轨迹摘要统计，对应 Go 版本 analyzer.go 的 buildSummary / haversineM / formatPace / SportTypeLabel。
 */
@Service
public class TrackSummaryService {

    /** 精度过滤阈值：水平精度超过 25m 的点（室内/弱信号漂移点）不参与统计，与 App 端 TrackStats.MAX_ACCURACY_M 一致。Accuracy 为 null 的点保留。 */
    private static final double MAX_ACCURACY_M = 25.0;

    /** 漂移过滤阈值：两点间移动速度超过 30 m/s（108 km/h）则丢弃该段。 */
    private static final double DRIFT_SPEED_LIMIT_MS = 30.0;

    private static final double EARTH_RADIUS_M = 6371000.0;

    public record SessionSummary(
            String sportType,
            double durationMin,
            double distanceKm,
            String avgPace,      // min/km
            double avgSpeedKmh,
            int avgHr,
            int avgCadence,    // 平均步频 SPM，仅跑步统计，无数据时为 0
            double avgStrideM, // 平均步幅 m/步，仅跑步统计，无数据时为 0
            double elevGain,
            String recentPace,   // 最近一段的配速，用于趋势判断
            int pointCount) {
    }

    /** 只给 AI 摘要统计而不是原始点，省 token 且分析更聚焦。步频/步幅仅跑步（run）统计，与 App 端一致。 */
    public SessionSummary buildSummary(List<TrackPoint> pts, String sportType, String sportTypeLabel) {
        SessionSummary base = new SessionSummary(sportTypeLabel, 0, 0, "", 0, 0, 0, 0, 0, "", pts.size());
        if (pts.isEmpty()) {
            return base;
        }

        double dist = 0;
        double elevGain = 0;
        double hrSum = 0;
        int hrCount = 0;
        long cadSum = 0;
        int cadCount = 0;
        TrackPoint prev = null;
        for (TrackPoint p : pts) {
            // 精度过滤：漂移点不参与距离/爬升，也不作为下一段的起点（心率仍统计）
            if (p.accuracy != null && p.accuracy > MAX_ACCURACY_M) {
                if (p.heartRate != null) {
                    hrSum += p.heartRate;
                    hrCount++;
                }
                continue;
            }
            if (prev != null) {
                double d = haversineM(prev.lat, prev.lng, p.lat, p.lng);
                // 过滤 GPS 漂移：两点间移动速度超过 30m/s 则跳过该段
                double dt = (p.timestamp - prev.timestamp) / 1000.0;
                if (dt > 0 && d / dt < DRIFT_SPEED_LIMIT_MS) {
                    dist += d;
                }
                if (prev.altitude != null && p.altitude != null && p.altitude > prev.altitude) {
                    elevGain += p.altitude - prev.altitude;
                }
            }
            if (p.heartRate != null) {
                hrSum += p.heartRate;
                hrCount++;
            }
            if (p.cadence != null && p.cadence > 0) {
                cadSum += p.cadence;
                cadCount++;
            }
            prev = p;
        }

        double durMin = (pts.get(pts.size() - 1).timestamp - pts.get(0).timestamp) / 60000.0;
        double distanceKm = dist / 1000.0;
        int avgHr = hrCount > 0 ? (int) (hrSum / hrCount) : 0;
        int avgCadence = 0;
        double avgStrideM = 0;
        if ("run".equals(sportType)) {
            avgCadence = cadCount > 0 ? (int) (cadSum / cadCount) : 0;
            // 平均步幅 = 总距离 / 总步数，总步数 = 平均步频(SPM) × 时长(分钟)，与 App 端 TrackStats.avgStrideLengthM 一致
            if (avgCadence > 0 && dist > 0 && durMin > 0) {
                avgStrideM = dist / (avgCadence * durMin);
            }
        }
        double avgSpeedKmh = 0;
        String avgPace = "";
        if (dist > 0 && durMin > 0) {
            avgSpeedKmh = distanceKm / (durMin / 60.0);
            avgPace = formatPace(durMin / distanceKm);
        }

        // 最近 20% 点位的配速，判断是否在掉速（同样做精度+漂移过滤）
        String recentPace = "";
        int n = pts.size() / 5;
        if (n >= 5) {
            List<TrackPoint> recent = pts.subList(pts.size() - n, pts.size());
            double rd = 0;
            TrackPoint rp = null;
            for (TrackPoint q : recent) {
                if (q.accuracy != null && q.accuracy > MAX_ACCURACY_M) {
                    continue;
                }
                if (rp != null) {
                    double d = haversineM(rp.lat, rp.lng, q.lat, q.lng);
                    double dt = (q.timestamp - rp.timestamp) / 1000.0;
                    if (dt > 0 && d / dt < DRIFT_SPEED_LIMIT_MS) {
                        rd += d;
                    }
                }
                rp = q;
            }
            double rdur = (recent.get(recent.size() - 1).timestamp - recent.get(0).timestamp) / 60000.0;
            if (rd > 0 && rdur > 0) {
                recentPace = formatPace(rdur / (rd / 1000.0));
            }
        }

        return new SessionSummary(sportTypeLabel, durMin, distanceKm, avgPace, avgSpeedKmh, avgHr,
                avgCadence, avgStrideM, elevGain, recentPace, pts.size());
    }

    /** 把 min/km（小数）格式化为 "5'32\"/km"。 */
    public String formatPace(double minPerKm) {
        if (minPerKm <= 0 || Double.isInfinite(minPerKm) || Double.isNaN(minPerKm)) {
            return "";
        }
        int min = (int) minPerKm;
        int sec = (int) ((minPerKm - min) * 60);
        return String.format(Locale.ROOT, "%d'%02d\"/km", min, sec);
    }

    /** 两坐标间球面距离（米）。 */
    public double haversineM(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** 把枚举转成中文，给 AI 提示词用（对应 Go SportTypeLabel）。 */
    public String sportTypeLabel(String t) {
        return switch (t) {
            case "run" -> "路跑";
            case "trail" -> "越野跑";
            case "bike" -> "骑行";
            default -> String.format("运动(%s)", t);
        };
    }
}
