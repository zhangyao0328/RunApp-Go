package com.runpulse.server.controller.admin;

import com.runpulse.server.repository.AdminRepository;
import com.runpulse.server.repository.RunPulseRepository;
import com.runpulse.server.service.TrackSummaryService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台仪表盘统计。
 */
@RestController
@RequestMapping("/admin/api/dashboard")
public class DashboardController {

    private static final int TREND_DAYS = 7;

    private final AdminRepository adminRepository;
    private final RunPulseRepository repository;
    private final TrackSummaryService summaryService;

    public DashboardController(AdminRepository adminRepository, RunPulseRepository repository,
                               TrackSummaryService summaryService) {
        this.adminRepository = adminRepository;
        this.repository = repository;
        this.summaryService = summaryService;
    }

    @GetMapping
    public AdminResponse<Map<String, Object>> overview() {
        long userCount = adminRepository.countActiveUsers();
        long sessionCount = repository.countSessions(null, null);
        long pointCount = repository.countPoints();
        long messageCount = repository.countMessages();

        // 总距离：对每个会话轨迹跑统计（当前数据量小；会话量大后应冗余 distance 列）
        double totalDistanceKm = repository.sessionPage(0, Integer.MAX_VALUE - 1, null, null).stream()
                .mapToDouble(s -> summaryService
                        .buildSummary(repository.sessionPoints(s.sessionId()),
                                s.sportType(), summaryService.sportTypeLabel(s.sportType()))
                        .distanceKm())
                .sum();

        long dayMillis = 24L * 60 * 60 * 1000;
        long now = System.currentTimeMillis();
        long todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userCount", userCount);
        data.put("sessionCount", sessionCount);
        data.put("pointCount", pointCount);
        data.put("messageCount", messageCount);
        data.put("totalDistanceKm", Math.round(totalDistanceKm * 100.0) / 100.0);
        data.put("todaySessionCount", repository.countSessionsSince(todayStart));

        // 近 7 天趋势：补零填充
        Map<String, Long> countByDay = new LinkedHashMap<>();
        for (RunPulseRepository.DayCount dc : repository.sessionCountsPerDay(now - (TREND_DAYS - 1L) * dayMillis)) {
            countByDay.put(dc.day(), dc.count());
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = TREND_DAYS - 1; i >= 0; i--) {
            String day = LocalDate.now().minusDays(i).toString();
            trend.add(Map.of("day", day, "count", countByDay.getOrDefault(day, 0L)));
        }
        data.put("trend", trend);
        data.put("sportDist", repository.sportTypeCounts());
        return AdminResponse.ok(data);
    }
}
