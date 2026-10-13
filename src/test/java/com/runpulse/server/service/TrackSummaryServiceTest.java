package com.runpulse.server.service;

import com.runpulse.server.model.TrackPoint;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 完整移植 Go 版本 analyzer_test.go 的 4 个场景。
 */
class TrackSummaryServiceTest {

    private final TrackSummaryService service = new TrackSummaryService();

    /** 构造一段沿经度匀速向东的轨迹：每 10 秒 0.001°（约 85m，8.5m/s 属于骑行速度）。 */
    private static List<TrackPoint> straightTrack(int n, double accuracy) {
        List<TrackPoint> pts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            TrackPoint p = new TrackPoint();
            p.lat = 39.9;
            p.lng = 116.3 + i * 0.001;
            p.accuracy = accuracy;
            p.timestamp = i * 10_000L;
            pts.add(p);
        }
        return pts;
    }

    @Test
    void cleanTrackDistanceAboutFiveKm() {
        List<TrackPoint> pts = straightTrack(60, 5); // 全部高精度
        TrackSummaryService.SessionSummary sum = service.buildSummary(pts, "run", "路跑");
        // 期望距离：59 段 × 0.001° 经度 × cos(39.9°) × 111320 ≈ 5.04km
        double want = 5.04;
        assertTrue(Math.abs(sum.distanceKm() - want) <= 0.3,
                String.format("干净轨迹距离偏差过大: got %.2f km, want ~%.2f km", sum.distanceKm(), want));
        assertFalse(sum.avgPace().isEmpty(), "干净轨迹应能算出配速");
    }

    @Test
    void filtersLowAccuracyDriftPoints() {
        List<TrackPoint> pts = straightTrack(60, 5);
        // 中段插入 10 个低精度漂移点（瞬移到 1km 外再回来）
        for (int i = 25; i < 35; i++) {
            pts.get(i).lat += 0.01; // ~1.1km 偏移
            pts.get(i).accuracy = 100.0;
        }
        TrackSummaryService.SessionSummary sum = service.buildSummary(pts, "run", "路跑");
        double want = 5.04;
        assertTrue(Math.abs(sum.distanceKm() - want) <= 0.3,
                String.format("低精度漂移点未被过滤: got %.2f km, want ~%.2f km", sum.distanceKm(), want));
    }

    @Test
    void filtersDriftSegments() {
        List<TrackPoint> pts = straightTrack(60, 5);
        // 一个精度正常但瞬移的漂移点（accuracy 好但物理上不可能）：10 秒移动 0.05° ≈ 4250m
        pts.get(30).lng += 0.05;
        TrackSummaryService.SessionSummary sum = service.buildSummary(pts, "run", "路跑");
        // 漂移段被 30m/s 阈值丢弃后，距离不应明显超过干净值（瞬移后的回程段也被丢）
        assertTrue(sum.distanceKm() <= 6.0,
                String.format("高速漂移段未被过滤: got %.2f km", sum.distanceKm()));
    }

    @Test
    void nullAccuracyPointsAreKept() {
        List<TrackPoint> pts = straightTrack(60, 5);
        for (TrackPoint p : pts) {
            p.accuracy = null; // GPX 导入等无精度数据的场景
        }
        TrackSummaryService.SessionSummary sum = service.buildSummary(pts, "run", "路跑");
        assertTrue(sum.distanceKm() >= 4.5,
                String.format("无精度字段的点不应被丢弃: got %.2f km", sum.distanceKm()));
    }

    @Test
    void emptyTrackReturnsZeroSummary() {
        TrackSummaryService.SessionSummary sum = service.buildSummary(List.of(), "run", "路跑");
        assertEquals(0, sum.distanceKm());
        assertEquals(0, sum.pointCount());
        assertEquals("路跑", sum.sportType());
    }
}
