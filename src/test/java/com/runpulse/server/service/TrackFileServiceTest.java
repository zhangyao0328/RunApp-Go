package com.runpulse.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.runpulse.server.model.TrackPoint;
import com.runpulse.server.repository.RunPulseRepository;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

/** TrackFileService 导入导出测试：GPX 用 Suunto 真实样例，FIT 走导出→导入 round-trip。 */
class TrackFileServiceTest {

    private RunPulseRepository repository;
    private TrackFileService service;

    @BeforeEach
    void setUp() {
        repository = mock(RunPulseRepository.class);
        service = new TrackFileService(repository);
    }

    private byte[] resource(String name) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "测试资源缺失: " + name);
            return in.readAllBytes();
        }
    }

    @Test
    void importSuuntoRunningGpx() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "suunto-run.gpx", "application/gpx+xml",
                resource("suuntoapp-Running-2026-08-03T12-10-22Z-track.gpx"));

        TrackFileService.ImportResult result = service.importFile(file, null);

        assertEquals("run", result.sportType());
        assertTrue(result.pointCount() > 100, "点数应远大于 100，实际 " + result.pointCount());
        assertTrue(result.sessionId().startsWith("imp-"));

        ArgumentCaptor<List<TrackPoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertSessionStart(eq(result.sessionId()), eq("run"), anyLong(), isNull());
        verify(repository, atLeastOnce()).insertPoints(eq(result.sessionId()), captor.capture());
        verify(repository).endSession(eq(result.sessionId()), anyLong());

        List<TrackPoint> all = new ArrayList<>();
        captor.getAllValues().forEach(all::addAll);
        TrackPoint first = all.get(0);
        assertEquals(39.979063, first.lat, 1e-6);
        assertEquals(116.353347, first.lng, 1e-6);
        assertNotNull(first.altitude);
        long expectedFirst = java.time.Instant.parse("2026-08-03T12:10:22Z").toEpochMilli();
        assertEquals(expectedFirst, first.timestamp, "时间戳应等于 GPX 首个 trkpt 时间");
    }

    @Test
    void importSuuntoCyclingGpx() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "suunto-bike.gpx", "application/gpx+xml",
                resource("suuntoapp-Cycling-2026-08-18T23-17-24Z-track.gpx"));

        TrackFileService.ImportResult result = service.importFile(file, 42L);

        assertEquals("run", result.sportType()); // GPX 无运动类型，默认 run
        assertTrue(result.pointCount() > 100, "点数应远大于 100，实际 " + result.pointCount());
    }

    @Test
    void rejectUnsupportedAndEmpty() {
        MockMultipartFile txt = new MockMultipartFile("file", "a.txt", "text/plain", "hi".getBytes());
        org.junit.jupiter.api.Assertions.assertThrows(
                com.runpulse.server.exception.ApiException.class, () -> service.importFile(txt, null));
        MockMultipartFile empty = new MockMultipartFile("file", "a.gpx", "application/gpx+xml", new byte[0]);
        org.junit.jupiter.api.Assertions.assertThrows(
                com.runpulse.server.exception.ApiException.class, () -> service.importFile(empty, null));
    }

    @Test
    void fitRoundTrip() {
        // 造 10 个合成点
        List<TrackPoint> points = new ArrayList<>();
        long base = 1725000000000L; // 2026-08-03T12:10:22Z 附近
        for (int i = 0; i < 10; i++) {
            TrackPoint p = new TrackPoint();
            p.lat = 39.979 + i * 0.0001;
            p.lng = 116.353 + i * 0.0001;
            p.altitude = 50.0 + i;
            p.speed = 10.0 + i; // km/h
            p.heartRate = 140 + i;
            p.cadence = 80 + i;
            p.timestamp = base + i * 1000L;
            points.add(p);
        }
        RunPulseRepository.SessionRow row =
                new RunPulseRepository.SessionRow("sess-1", "run", base, base + 9000, 10, 0, null);
        when(repository.findSessionRow("sess-1")).thenReturn(Optional.of(row));
        when(repository.sessionPoints("sess-1")).thenReturn(points);

        byte[] fit = service.exportFit("sess-1");
        assertTrue(fit.length > 100, "FIT 文件不应为空");

        // 再导入
        MockMultipartFile file = new MockMultipartFile("file", "roundtrip.fit", "application/octet-stream", fit);
        TrackFileService.ImportResult result = service.importFile(file, 42L);
        // round-trip 会话归属也应带同一用户
        verify(repository).upsertSessionStart(eq(result.sessionId()), eq("run"), anyLong(), eq(42L));

        assertEquals("run", result.sportType());
        assertEquals(10, result.pointCount());

        ArgumentCaptor<List<TrackPoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).insertPoints(anyString(), captor.capture());
        List<TrackPoint> imported = captor.getValue();
        assertEquals(10, imported.size());
        for (int i = 0; i < 10; i++) {
            TrackPoint src = points.get(i);
            TrackPoint dst = imported.get(i);
            assertEquals(src.timestamp, dst.timestamp, "第 " + i + " 点时间戳");
            assertEquals(src.lat, dst.lat, 1e-5, "第 " + i + " 点纬度");
            assertEquals(src.lng, dst.lng, 1e-5, "第 " + i + " 点经度");
            assertEquals(src.altitude, dst.altitude, 0.5, "第 " + i + " 点海拔");
            assertEquals(src.heartRate, dst.heartRate, "第 " + i + " 点心率");
            assertEquals(src.cadence, dst.cadence, "第 " + i + " 点步频");
            assertEquals(src.speed, dst.speed, 0.01, "第 " + i + " 点速度");
        }
    }

    @Test
    void exportGpxContainsTrackPoints() {
        List<TrackPoint> points = new ArrayList<>();
        TrackPoint p = new TrackPoint();
        p.lat = 39.979063;
        p.lng = 116.353347;
        p.altitude = 53.0;
        p.heartRate = 152;
        p.cadence = 85;
        p.timestamp = 1725000000000L;
        points.add(p);
        TrackPoint p2 = new TrackPoint();
        p2.lat = 39.979065;
        p2.lng = 116.353383;
        p2.timestamp = 1725001000000L;
        points.add(p2);
        RunPulseRepository.SessionRow row =
                new RunPulseRepository.SessionRow("sess-2", "run", p.timestamp, p2.timestamp, 2, 0, null);
        when(repository.findSessionRow("sess-2")).thenReturn(Optional.of(row));
        when(repository.sessionPoints("sess-2")).thenReturn(points);

        String gpx = new String(service.exportGpx("sess-2"), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(gpx.contains("<trkpt lat=\"39.979063\" lon=\"116.353347\">"));
        assertTrue(gpx.contains("<gpxtpx:hr>152</gpxtpx:hr>"));
        assertTrue(gpx.contains("<gpxtpx:cad>85</gpxtpx:cad>"));
        assertTrue(gpx.contains("creator=\"RunPulse\""));
    }
}
