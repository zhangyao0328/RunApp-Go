package com.runpulse.server.controller.admin;

import com.runpulse.server.exception.ApiException;
import com.runpulse.server.model.TrackPoint;
import com.runpulse.server.repository.RunPulseRepository;
import com.runpulse.server.service.TrackFileService;
import com.runpulse.server.service.TrackSummaryService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 管理后台：运动记录查看。
 */
@RestController
@RequestMapping("/admin/api/sessions")
public class SessionAdminController {

    /** 详情页轨迹点上限，防止超大轨迹撑爆响应。 */
    private static final int MAX_DETAIL_POINTS = 20000;

    private final RunPulseRepository repository;
    private final TrackSummaryService summaryService;
    private final TrackFileService trackFileService;

    public SessionAdminController(RunPulseRepository repository,
                                  TrackSummaryService summaryService,
                                  TrackFileService trackFileService) {
        this.repository = repository;
        this.summaryService = summaryService;
        this.trackFileService = trackFileService;
    }

    @GetMapping
    public AdminResponse<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "10") int pageSize,
                                                   @RequestParam(required = false) String sportType,
                                                   @RequestParam(required = false) Long userId) {
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), 100);
        // 前端空串参数视为"不按类型筛选"：空串不是 NULL，直接透传会过滤成 sport_type='' 导致空列表
        if (sportType != null && sportType.isBlank()) {
            sportType = null;
        }
        List<RunPulseRepository.SessionRow> sessions =
                repository.sessionPage((page - 1) * pageSize, pageSize, sportType, userId);
        return AdminResponse.ok(Map.of(
                "list", sessions,
                "total", repository.countSessions(sportType, userId)));
    }

    /** 详情：会话信息 + 统计摘要 + 轨迹点（≤20000）+ AI 点评。 */
    @GetMapping("/{sessionId}")
    public AdminResponse<Map<String, Object>> detail(@PathVariable String sessionId) {
        RunPulseRepository.SessionRow session = repository.findSessionRow(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "运动记录不存在"));
        List<TrackPoint> points = repository.sessionPoints(sessionId);
        if (points.size() > MAX_DETAIL_POINTS) {
            points = points.subList(0, MAX_DETAIL_POINTS);
        }
        TrackSummaryService.SessionSummary summary =
                summaryService.buildSummary(points, session.sportType(),
                        summaryService.sportTypeLabel(session.sportType()));
        return AdminResponse.ok(Map.of(
                "session", session,
                "summary", summary,
                "points", points,
                "messages", repository.analysisSince(sessionId, 0)));
    }

    /** 导出运动记录为 GPX / FIT 文件。 */
    @GetMapping("/{sessionId}/export")
    public ResponseEntity<byte[]> export(@PathVariable String sessionId,
                                         @RequestParam(defaultValue = "gpx") String format) {
        String shortId = sessionId.length() > 8 ? sessionId.substring(0, 8) : sessionId;
        return switch (format) {
            case "gpx" -> fileResponse(
                    trackFileService.exportGpx(sessionId),
                    "runpulse-" + shortId + ".gpx",
                    MediaType.parseMediaType("application/gpx+xml"));
            case "fit" -> fileResponse(
                    trackFileService.exportFit(sessionId),
                    "runpulse-" + shortId + ".fit",
                    MediaType.parseMediaType("application/octet-stream"));
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "format 仅支持 gpx / fit");
        };
    }

    /** 导入 GPX / FIT 文件为新运动记录。 */
    @PostMapping("/import")
    public AdminResponse<Map<String, Object>> importTrack(@RequestParam("file") MultipartFile file) {
        TrackFileService.ImportResult result = trackFileService.importFile(file, null);
        return AdminResponse.ok(Map.of(
                "sessionId", result.sessionId(),
                "sportType", result.sportType(),
                "pointCount", result.pointCount()));
    }

    private ResponseEntity<byte[]> fileResponse(byte[] body, String filename, MediaType type) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded)
                .body(body);
    }
}
