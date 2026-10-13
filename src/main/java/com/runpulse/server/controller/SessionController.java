package com.runpulse.server.controller;

import com.runpulse.server.dto.SessionEndRequest;
import com.runpulse.server.dto.SessionStartRequest;
import com.runpulse.server.exception.ApiException;
import com.runpulse.server.model.AnalysisMessage;
import com.runpulse.server.repository.RunPulseRepository;
import com.runpulse.server.service.TrackFileService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final RunPulseRepository repository;
    private final TrackFileService trackFileService;

    public SessionController(RunPulseRepository repository, TrackFileService trackFileService) {
        this.repository = repository;
        this.trackFileService = trackFileService;
    }

    /** 幂等 upsert：App 可能因进程被杀重启后重复上报 start。 */
    @PostMapping("/api/v1/sessions/start")
    public Map<String, String> start(@RequestBody(required = false) SessionStartRequest req,
                                     @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        if (req == null || req.sessionId() == null || req.sessionId().isEmpty()) {
            throw ApiException.badRequest("bad request");
        }
        // Go 版本缺失 sport_type 时写入空字符串；这里显式归一化，避免 NULL 触发非空约束。
        String sportType = req.sportType() == null ? "" : req.sportType();
        Long userId = resolveUserId(deviceId);
        repository.upsertSessionStart(req.sessionId(), sportType, req.startTime(), userId);
        log.info("session_start id={} sport={} start={} userId={}", req.sessionId(), sportType, req.startTime(), userId);
        return Map.of("status", "ok");
    }

    @PostMapping("/api/v1/sessions/end")
    public Map<String, String> end(@RequestBody(required = false) SessionEndRequest req) {
        if (req == null || req.sessionId() == null || req.sessionId().isEmpty()) {
            throw ApiException.badRequest("bad request");
        }
        repository.endSession(req.sessionId(), req.endTime());
        log.info("session_end id={} end={}", req.sessionId(), req.endTime());
        return Map.of("status", "ok");
    }

    /** App 端导入 GPX/FIT 轨迹文件（从手机存储选择），复用管理端导入逻辑。 */
    @PostMapping("/api/v1/sessions/import")
    public Map<String, Object> importTrack(@RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                           @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        Long userId = resolveUserId(deviceId);
        TrackFileService.ImportResult result = trackFileService.importFile(file, userId);
        log.info("session_import id={} sport={} points={} userId={}", result.sessionId(), result.sportType(),
                result.pointCount(), userId);
        return Map.of("sessionId", result.sessionId(), "sportType", result.sportType(), "pointCount", result.pointCount());
    }

    /** X-Device-Id 解析为运动用户 id：老客户端未带头时为 null（会话保持未归属）。 */
    private Long resolveUserId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        return repository.findOrCreateAppUser(deviceId.trim(), System.currentTimeMillis());
    }

    /** 增量拉取分析消息，App 端用 since_id 轮询；messages 永不为 null。 */
    @GetMapping("/api/v1/sessions/{sessionId}/analysis")
    public Map<String, Object> analysis(@PathVariable String sessionId,
                                        @RequestParam(value = "since_id", required = false) String sinceId) {
        long since = 0;
        if (sinceId != null && !sinceId.isEmpty()) {
            try {
                since = Long.parseLong(sinceId.trim());
            } catch (NumberFormatException ignored) {
                since = 0; // 与 Go 版本一致：解析失败按 0 处理
            }
        }
        List<AnalysisMessage> msgs;
        try {
            msgs = repository.analysisSince(sessionId, since);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
        if (msgs == null) {
            msgs = new ArrayList<>(); // 保证返回 [] 而不是 null，方便客户端解析
        }
        return Map.of("messages", msgs);
    }
}
