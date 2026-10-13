package com.runpulse.server.controller;

import com.runpulse.server.dto.PointsBatchRequest;
import com.runpulse.server.exception.ApiException;
import com.runpulse.server.model.TrackPoint;
import com.runpulse.server.repository.RunPulseRepository;
import com.runpulse.server.service.CoachAnalysisService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PointController {

    private static final Logger log = LoggerFactory.getLogger(PointController.class);

    private static final int MAX_BATCH = 500;

    private final RunPulseRepository repository;
    private final CoachAnalysisService coachAnalysisService;

    public PointController(RunPulseRepository repository, CoachAnalysisService coachAnalysisService) {
        this.repository = repository;
        this.coachAnalysisService = coachAnalysisService;
    }

    @PostMapping("/api/v1/points/batch")
    public Map<String, Object> batch(@RequestBody(required = false) PointsBatchRequest req) {
        if (req == null || req.sessionId() == null || req.sessionId().isEmpty()
                || req.points() == null || req.points().isEmpty()) {
            throw ApiException.badRequest("bad request");
        }
        List<TrackPoint> points = req.points();
        // 单批上限保护，防异常客户端打爆内存
        if (points.size() > MAX_BATCH) {
            throw ApiException.badRequest("batch too large");
        }
        try {
            repository.insertPoints(req.sessionId(), points);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
        // 记录批次概况：点数 + 精度分布（好/差），漂移分析的第一手材料
        int bad = 0;
        long minTs = Long.MAX_VALUE;
        long maxTs = Long.MIN_VALUE;
        for (TrackPoint p : points) {
            if (p.accuracy != null && p.accuracy > 25) {
                bad++;
            }
            if (p.timestamp < minTs) {
                minTs = p.timestamp;
            }
            if (p.timestamp > maxTs) {
                maxTs = p.timestamp;
            }
        }
        log.info("points_batch session={} count={} bad_accuracy={} span={}ms",
                req.sessionId(), points.size(), bad, maxTs - minTs);
        // 落库成功后异步触发分析检查，不阻塞响应
        coachAnalysisService.maybeAnalyze(req.sessionId());
        return Map.of("received", points.size());
    }
}
