package com.runpulse.server.service;

import com.garmin.fit.Activity;
import com.garmin.fit.ActivityMesg;
import com.garmin.fit.DateTime;
import com.garmin.fit.Event;
import com.garmin.fit.EventType;
import com.garmin.fit.File;
import com.garmin.fit.FileEncoder;
import com.garmin.fit.FileIdMesg;
import com.garmin.fit.FitRuntimeException;
import com.garmin.fit.LapMesg;
import com.garmin.fit.Manufacturer;
import com.garmin.fit.MesgBroadcaster;
import com.garmin.fit.RecordMesg;
import com.garmin.fit.SessionMesg;
import com.garmin.fit.Sport;
import com.garmin.fit.SubSport;
import com.runpulse.server.exception.ApiException;
import com.runpulse.server.model.TrackPoint;
import com.runpulse.server.repository.RunPulseRepository;
import io.jenetics.jpx.GPX;
import io.jenetics.jpx.Length;
import io.jenetics.jpx.Track;
import io.jenetics.jpx.TrackSegment;
import io.jenetics.jpx.WayPoint;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * 运动数据文件导入/导出：GPX 1.1（含 gpxtpx 心率/步频扩展）与 FIT（Garmin 官方 SDK）。
 * 坐标一律 WGS84 原值，不做任何纠偏。
 */
@Service
public class TrackFileService {

    private static final double SEMICIRCLE_PER_DEG = 11930465.0; // 2^31 / 180
    private static final int BATCH_SIZE = 500;
    private static final int MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB

    private static final DateTimeFormatter GPX_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final RunPulseRepository repository;

    public TrackFileService(RunPulseRepository repository) {
        this.repository = repository;
    }

    public record ImportResult(String sessionId, String sportType, int pointCount) {}

    // ---------------------------------------------------------------- 导出

    /** 导出会话为 GPX 1.1 字节流。 */
    public byte[] exportGpx(String sessionId) {
        List<TrackPoint> points = requirePoints(sessionId);
        RunPulseRepository.SessionRow session = requireSession(sessionId);

        StringBuilder sb = new StringBuilder(points.size() * 120 + 400);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<gpx version=\"1.1\" creator=\"RunPulse\" ")
          .append("xmlns=\"http://www.topografix.com/GPX/1/1\" ")
          .append("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\" ")
          .append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
          .append("xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n");
        sb.append("<metadata><name>RunPulse ").append(sessionId).append("</name><time>")
          .append(GPX_TIME.format(Instant.ofEpochMilli(session.startTime()))).append("</time></metadata>\n");
        sb.append("<trk><name>RunPulse ").append(session.sportType()).append(' ').append(GPX_TIME.format(Instant.ofEpochMilli(session.startTime())))
          .append("</name><trkseg>\n");
        for (TrackPoint p : points) {
            sb.append("<trkpt lat=\"").append(p.lat).append("\" lon=\"").append(p.lng).append("\">");
            if (p.altitude != null) {
                sb.append("<ele>").append(p.altitude).append("</ele>");
            }
            sb.append("<time>").append(GPX_TIME.format(Instant.ofEpochMilli(p.timestamp))).append("</time>");
            if (p.heartRate != null || p.cadence != null) {
                sb.append("<extensions><gpxtpx:TrackPointExtension>");
                if (p.heartRate != null) {
                    sb.append("<gpxtpx:hr>").append(p.heartRate).append("</gpxtpx:hr>");
                }
                if (p.cadence != null) {
                    sb.append("<gpxtpx:cad>").append(p.cadence).append("</gpxtpx:cad>");
                }
                sb.append("</gpxtpx:TrackPointExtension></extensions>");
            }
            sb.append("</trkpt>\n");
        }
        sb.append("</trkseg></trk>\n</gpx>\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 导出会话为 FIT Activity 文件字节流。 */
    public byte[] exportFit(String sessionId) {
        List<TrackPoint> points = requirePoints(sessionId);
        RunPulseRepository.SessionRow session = requireSession(sessionId);
        Sport sport = toFitSport(session.sportType());

        long startMs = points.get(0).timestamp;
        long endMs = points.get(points.size() - 1).timestamp;
        float elapsedSec = (endMs - startMs) / 1000f;
        double distanceM = haversineMeters(points);

        Path tmp;
        try {
            tmp = Files.createTempFile("runpulse-export-", ".fit");
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "导出失败: " + e.getMessage());
        }

        try {
            FileEncoder encoder = new FileEncoder(tmp.toFile(), com.garmin.fit.Fit.ProtocolVersion.V2_0);

            FileIdMesg fileId = new FileIdMesg();
            fileId.setType(File.ACTIVITY);
            fileId.setManufacturer(Manufacturer.DEVELOPMENT);
            fileId.setProduct(0);
            fileId.setSerialNumber(0L);
            fileId.setTimeCreated(fitTime(startMs));
            encoder.write(fileId);

            for (TrackPoint p : points) {
                RecordMesg rec = new RecordMesg();
                rec.setTimestamp(fitTime(p.timestamp));
                rec.setPositionLat((int) Math.round(p.lat * SEMICIRCLE_PER_DEG));
                rec.setPositionLong((int) Math.round(p.lng * SEMICIRCLE_PER_DEG));
                if (p.altitude != null) {
                    rec.setAltitude(p.altitude.floatValue());
                }
                if (p.speed != null) {
                    rec.setSpeed((float) (p.speed / 3.6)); // km/h -> m/s
                }
                if (p.heartRate != null) {
                    rec.setHeartRate(p.heartRate.shortValue());
                }
                if (p.cadence != null) {
                    rec.setCadence(p.cadence.shortValue());
                }
                encoder.write(rec);
            }

            LapMesg lap = new LapMesg();
            lap.setTimestamp(fitTime(endMs));
            lap.setStartTime(fitTime(startMs));
            lap.setTotalElapsedTime(elapsedSec);
            lap.setTotalTimerTime(elapsedSec);
            lap.setTotalDistance((float) distanceM);
            lap.setSport(sport);
            encoder.write(lap);

            SessionMesg sess = new SessionMesg();
            sess.setTimestamp(fitTime(endMs));
            sess.setStartTime(fitTime(startMs));
            sess.setSport(sport);
            sess.setSubSport(SubSport.GENERIC);
            sess.setTotalElapsedTime(elapsedSec);
            sess.setTotalTimerTime(elapsedSec);
            sess.setTotalDistance((float) distanceM);
            sess.setNumLaps(1);
            encoder.write(sess);

            ActivityMesg activity = new ActivityMesg();
            activity.setTimestamp(fitTime(endMs));
            activity.setTotalTimerTime(elapsedSec);
            activity.setNumSessions(1);
            activity.setType(Activity.MANUAL);
            activity.setEvent(Event.ACTIVITY);
            activity.setEventType(EventType.STOP);
            encoder.write(activity);

            encoder.close();
            return Files.readAllBytes(tmp);
        } catch (FitRuntimeException | IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "FIT 导出失败: " + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 临时文件清理失败不影响主流程
            }
        }
    }

    // ---------------------------------------------------------------- 导入

    /** 解析上传的 GPX/FIT 文件并入库为一个新会话（幂等，可重复上传）。userId 为归属运动用户，可为 null。 */
    public ImportResult importFile(MultipartFile file, Long userId) {
        if (file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "文件为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "文件超过 50MB 上限");
        }
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "读取文件失败: " + e.getMessage());
        }

        ParsedTrack parsed;
        if (name.endsWith(".gpx")) {
            parsed = parseGpx(bytes);
        } else if (name.endsWith(".fit")) {
            parsed = parseFit(bytes);
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "仅支持 .gpx / .fit 文件");
        }
        if (parsed.points().size() < 2) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "有效轨迹点不足 2 个，无法导入");
        }

        List<TrackPoint> points = parsed.points();
        String sessionId = newSessionId();
        repository.upsertSessionStart(sessionId, parsed.sportType(), points.get(0).timestamp, userId);
        for (int from = 0; from < points.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, points.size());
            repository.insertPoints(sessionId, points.subList(from, to));
        }
        repository.endSession(sessionId, points.get(points.size() - 1).timestamp);
        return new ImportResult(sessionId, parsed.sportType(), points.size());
    }

    private record ParsedTrack(String sportType, List<TrackPoint> points) {}

    /** GPX 导入：JPX 解析，读取所有 trkseg 的 trkpt 及 gpxtpx 心率/步频扩展。 */
    private ParsedTrack parseGpx(byte[] bytes) {
        Path tmp;
        try {
            tmp = Files.createTempFile("runpulse-import-", ".gpx");
            Files.write(tmp, bytes);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GPX 解析失败: " + e.getMessage());
        }
        try {
            GPX gpx = GPX.read(tmp);
            List<TrackPoint> points = new ArrayList<>();
            for (Track track : gpx.getTracks()) {
                for (TrackSegment segment : track.getSegments()) {
                    for (WayPoint wp : segment.getPoints()) {
                        if (wp.getTime().isEmpty()) {
                            continue; // 无时间戳的点无法入库
                        }
                        TrackPoint p = new TrackPoint();
                        p.lat = wp.getLatitude().doubleValue();
                        p.lng = wp.getLongitude().doubleValue();
                        wp.getElevation().map(Length::doubleValue).ifPresent(e -> p.altitude = e);
                        p.timestamp = wp.getTime().get().toEpochMilli();
                        readGpxExtensions(wp, p);
                        points.add(p);
                    }
                }
            }
            points.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));
            return new ParsedTrack("run", points);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GPX 解析失败: " + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
        }
    }

    /** 从 gpxtpx:TrackPointExtension 提取心率/步频（命名空间不敏感）。 */
    private void readGpxExtensions(WayPoint wp, TrackPoint p) {
        Optional<Document> ext = wp.getExtensions();
        if (ext.isEmpty()) {
            return;
        }
        Element root = ext.get().getDocumentElement();
        NodeList hrs = root.getElementsByTagNameNS("*", "hr");
        if (hrs.getLength() > 0) {
            p.heartRate = parseIntOrNull(hrs.item(0).getTextContent());
        }
        NodeList cads = root.getElementsByTagNameNS("*", "cad");
        if (cads.getLength() > 0) {
            p.cadence = parseIntOrNull(cads.item(0).getTextContent());
        }
    }

    /** FIT 导入：Garmin SDK 解码，收集 Record 点与 Session 运动类型。 */
    private ParsedTrack parseFit(byte[] bytes) {
        List<TrackPoint> points = new ArrayList<>();
        Sport[] sportRef = new Sport[1];
        try {
            MesgBroadcaster broadcaster = new MesgBroadcaster();
            broadcaster.addListener((com.garmin.fit.RecordMesgListener) rec -> {
                if (rec.getTimestamp() == null
                        || rec.getTimestamp().getInstant() == null
                        || rec.getPositionLat() == null
                        || rec.getPositionLong() == null) {
                    return;
                }
                TrackPoint p = new TrackPoint();
                p.lat = rec.getPositionLat() / SEMICIRCLE_PER_DEG;
                p.lng = rec.getPositionLong() / SEMICIRCLE_PER_DEG;
                if (rec.getAltitude() != null) {
                    p.altitude = rec.getAltitude().doubleValue();
                }
                if (rec.getSpeed() != null) {
                    p.speed = rec.getSpeed() * 3.6; // m/s -> km/h
                }
                if (rec.getHeartRate() != null) {
                    p.heartRate = rec.getHeartRate().intValue();
                }
                if (rec.getCadence() != null) {
                    p.cadence = rec.getCadence().intValue();
                }
                p.timestamp = rec.getTimestamp().getInstant().toEpochMilli();
                points.add(p);
            });
            broadcaster.addListener((com.garmin.fit.SessionMesgListener) sess -> {
                if (sportRef[0] == null && sess.getSport() != null) {
                    sportRef[0] = sess.getSport();
                }
            });
            broadcaster.run(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FIT 解析失败: " + e.getMessage());
        }
        points.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));
        return new ParsedTrack(fromFitSport(sportRef[0]), points);
    }

    // ---------------------------------------------------------------- 工具

    private RunPulseRepository.SessionRow requireSession(String sessionId) {
        return repository.findSessionRow(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "运动记录不存在"));
    }

    private List<TrackPoint> requirePoints(String sessionId) {
        List<TrackPoint> points = repository.sessionPoints(sessionId);
        if (points.size() < 2) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "轨迹点不足，无法导出");
        }
        return points;
    }

    private static DateTime fitTime(long unixMs) {
        return DateTime.from(Instant.ofEpochMilli(unixMs));
    }

    private static Sport toFitSport(String sportType) {
        if ("bike".equals(sportType)) {
            return Sport.CYCLING;
        }
        if ("trail".equals(sportType)) {
            return Sport.HIKING;
        }
        return Sport.RUNNING;
    }

    private static String fromFitSport(Sport sport) {
        if (sport == null) {
            return "run";
        }
        return switch (sport) {
            case CYCLING -> "bike";
            case HIKING, WALKING, MOUNTAINEERING -> "trail";
            default -> "run";
        };
    }

    private static double haversineMeters(List<TrackPoint> points) {
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            total += haversine(points.get(i - 1).lat, points.get(i - 1).lng, points.get(i).lat, points.get(i).lng);
        }
        return total;
    }

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    private static Integer parseIntOrNull(String s) {
        try {
            return s == null ? null : Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String newSessionId() {
        return "imp-" + java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "-" + Integer.toHexString(new Random().nextInt(0x10000));
    }
}
