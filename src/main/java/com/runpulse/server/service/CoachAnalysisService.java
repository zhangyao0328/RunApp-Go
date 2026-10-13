package com.runpulse.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runpulse.server.config.RunpulseProperties;
import com.runpulse.server.model.TrackPoint;
import com.runpulse.server.repository.RunPulseRepository;
import com.runpulse.server.service.TrackSummaryService.SessionSummary;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 实时分析器，对应 Go 版本 analyzer.go 的 Analyzer。
 * 每次轨迹批量上传落库后检查是否攒够数据，攒够则异步调 AI 分析。
 * 用 ConcurrentHashMap 做 per-session 的 in-flight 去重，避免并发重复调用 LLM。
 */
@Service
public class CoachAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(CoachAnalysisService.class);

    private static final String COACH_SYSTEM_PROMPT = String.join("\n",
            "你是一位专业且克制的跑步教练，正在通过耳机对运动中的学员做实时语音指导。",
            "规则：",
            "- 只输出 1-2 句中文口语化点评，总字数不超过 60 字（会被 TTS 直接播报）",
            "- 先肯定当前状态，有异常再给一条具体建议（如\"心率偏高，放慢 10 秒配速\"）",
            "- 不要使用表情符号、列表、标题等任何富文本格式",
            "- 数据正常时就给一句简短鼓励，不要啰嗦",
            "- 已经提醒过的问题（如未佩戴心率设备）不要反复提醒");

    private final RunpulseProperties props;
    private final RunPulseRepository repository;
    private final TrackSummaryService trackSummaryService;
    private final OpenClawClient openClawClient;
    private final Executor analysisExecutor;

    /** sessionID -> 占位，in-flight 去重 */
    private final Set<String> inflight = ConcurrentHashMap.newKeySet();

    public CoachAnalysisService(RunpulseProperties props,
                                RunPulseRepository repository,
                                TrackSummaryService trackSummaryService,
                                ObjectMapper objectMapper,
                                @Qualifier("analysisExecutor") Executor analysisExecutor) {
        this.props = props;
        this.repository = repository;
        this.trackSummaryService = trackSummaryService;
        this.openClawClient = new OpenClawClient(props, objectMapper);
        this.analysisExecutor = analysisExecutor;
    }

    /** 由上传接口在成功落库后调用，立即返回，不阻塞 App 响应。 */
    public void maybeAnalyze(String sessionId) {
        if (props.getOpenclawBaseUrl().isEmpty() || props.getOpenclawModel().isEmpty()) {
            return; // AI 未配置，静默跳过
        }
        boolean pending;
        try {
            pending = repository.pendingAnalysis(sessionId,
                    props.getAnalysisMinPoints(), props.getAnalysisMinIntervalSec());
        } catch (Exception e) {
            log.warn("[analyzer] session {} 查询分析进度失败: {}", sessionId, e.getMessage());
            return;
        }
        if (!pending) {
            return;
        }
        if (!inflight.add(sessionId)) {
            return;
        }
        try {
            analysisExecutor.execute(() -> {
                try {
                    analyze(sessionId);
                } catch (Exception e) {
                    log.warn("[analyzer] session {} 分析失败: {}", sessionId, e.getMessage());
                } finally {
                    inflight.remove(sessionId);
                }
            });
        } catch (RejectedExecutionException e) {
            inflight.remove(sessionId);
            log.warn("[analyzer] session {} 分析任务被线程池拒绝: {}", sessionId, e.getMessage());
        }
    }

    private void analyze(String sessionId) throws Exception {
        List<TrackPoint> pts = repository.sessionPoints(sessionId);
        if (pts.isEmpty()) {
            return;
        }

        String sportType = repository.findSportType(sessionId);
        SessionSummary summary =
                trackSummaryService.buildSummary(pts, sportType, trackSummaryService.sportTypeLabel(sportType));
        String prompt = buildPrompt(summary);

        String text = openClawClient.chat(COACH_SYSTEM_PROMPT, prompt);

        long lastId = pts.get(pts.size() - 1).id;
        repository.saveAnalysis(sessionId, text, lastId);
        String preview = text.length() > 60 ? text.substring(0, 60) : text;
        log.info("[analyzer] session {} 生成点评: {}...", sessionId, preview);
    }

    private String buildPrompt(SessionSummary s) {
        return String.format(Locale.ROOT, String.join("\n",
                "运动类型：%s",
                "已运动：%.1f 分钟，距离 %.2f km，累计爬升 %.0f m",
                "平均配速：%s，最近一段配速：%s",
                "平均心率：%d（0 表示未佩戴心率设备）",
                "请给出当前阶段的实时指导。"),
                s.sportType(), s.durationMin(), s.distanceKm(), s.elevGain(),
                orNA(s.avgPace()), orNA(s.recentPace()), s.avgHr());
    }

    private String orNA(String s) {
        return s == null || s.isEmpty() ? "数据不足" : s;
    }
}
