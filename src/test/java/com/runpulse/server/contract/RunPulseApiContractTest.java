package com.runpulse.server.contract;

import com.runpulse.server.config.RunpulseProperties;
import com.runpulse.server.controller.EventController;
import com.runpulse.server.controller.HealthController;
import com.runpulse.server.controller.PointController;
import com.runpulse.server.controller.SessionController;
import com.runpulse.server.repository.RunPulseRepository;
import com.runpulse.server.security.JwtService;
import com.runpulse.server.service.CoachAnalysisService;
import com.runpulse.server.service.TrackFileService;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API 契约测试：@WebMvcTest + MockMvc + mocked repository/analysis service，不触真实数据库。
 */
/** 只加载 App API 四个 controller；AdminAuthFilter 作为 Filter 仍会被注册，需 mock JwtService。 */
@WebMvcTest({HealthController.class, SessionController.class, PointController.class, EventController.class})
class RunPulseApiContractTest {

    private static final String API_KEY = "test-secret-key";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RunPulseRepository repository;

    @MockBean
    private CoachAnalysisService coachAnalysisService;

    /** RunpulseProperties 是普通 @Component，@WebMvcTest 不会加载，这里 mock 并提供 API key。 */
    @MockBean
    private RunpulseProperties props;

    /** AdminAuthFilter 是注册的 Filter Bean，需 mock 其依赖（本测试不涉及 /admin 路径）。 */
    @MockBean
    private JwtService jwtService;

    /** SessionController 导入端点依赖（本测试不涉及导入）。 */
    @MockBean
    private TrackFileService trackFileService;

    @BeforeEach
    void setUp() {
        when(props.getApiKey()).thenReturn(API_KEY);
    }

    private ResultActions authedPost(String path, String body) throws Exception {
        return mockMvc.perform(post(path)
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // ---------- 鉴权 ----------

    @Test
    void healthzIsPublic() throws Exception {
        mockMvc.perform(get("/healthz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void wrongApiKeyReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/sessions/start")
                        .header("X-API-Key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\":\"s1\",\"start_time\":123}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid api key"));
        verify(repository, never()).upsertSessionStart(anyString(), anyString(), any(Long.class), any());
    }

    // ---------- sessions ----------

    @Test
    void startSucceeds() throws Exception {
        authedPost("/api/v1/sessions/start",
                        "{\"session_id\":\"s1\",\"sport_type\":\"run\",\"start_time\":1234567890}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
        // 老客户端未带 X-Device-Id：归属为 null
        verify(repository).upsertSessionStart("s1", "run", 1234567890L, null);
    }

    @Test
    void startWithDeviceIdBindsAppUser() throws Exception {
        when(repository.findOrCreateAppUser(eq("dev-1"), anyLong())).thenReturn(7L);
        mockMvc.perform(post("/api/v1/sessions/start")
                        .header("X-API-Key", API_KEY)
                        .header("X-Device-Id", "dev-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\":\"s2\",\"sport_type\":\"run\",\"start_time\":123}"))
                .andExpect(status().isOk());
        verify(repository).findOrCreateAppUser(eq("dev-1"), anyLong());
        verify(repository).upsertSessionStart("s2", "run", 123L, 7L);
    }

    @Test
    void invalidJsonReturns400() throws Exception {
        authedPost("/api/v1/sessions/start", "{bad json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad request"));
        verify(repository, never()).upsertSessionStart(anyString(), anyString(), any(Long.class), any());
    }

    @Test
    void analysisReturnsEmptyMessagesWhenNoResult() throws Exception {
        when(repository.analysisSince(eq("s1"), eq(0L))).thenReturn(Collections.emptyList());
        mockMvc.perform(get("/api/v1/sessions/s1/analysis")
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isArray())
                .andExpect(jsonPath("$.messages").isEmpty());
    }

    @Test
    void analysisPropagatesMessages() throws Exception {
        var msg = new com.runpulse.server.model.AnalysisMessage(7, 1700000000000L, "配速稳定");
        when(repository.analysisSince(eq("s1"), eq(0L))).thenReturn(List.of(msg));
        mockMvc.perform(get("/api/v1/sessions/s1/analysis")
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].id").value(7))
                .andExpect(jsonPath("$.messages[0].text").value("配速稳定"));
    }

    // ---------- points ----------

    @Test
    void pointsEmptyReturns400AndRepositoryNotCalled() throws Exception {
        authedPost("/api/v1/points/batch",
                        "{\"session_id\":\"s1\",\"points\":[]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad request"));
        verify(repository, never()).insertPoints(anyString(), anyList());
        verify(coachAnalysisService, never()).maybeAnalyze(anyString());
    }

    @Test
    void pointsOver500Returns400AndRepositoryNotCalled() throws Exception {
        StringBuilder body = new StringBuilder("{\"session_id\":\"s1\",\"points\":[");
        for (int i = 0; i < 501; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"lat\":39.9,\"lng\":116.3,\"timestamp\":").append(i * 10000L).append('}');
        }
        body.append("]}");
        authedPost("/api/v1/points/batch", body.toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("batch too large"));
        verify(repository, never()).insertPoints(anyString(), anyList());
        verify(coachAnalysisService, never()).maybeAnalyze(anyString());
    }

    @Test
    void pointsBatchSucceeds() throws Exception {
        authedPost("/api/v1/points/batch",
                        "{\"session_id\":\"s1\",\"points\":["
                                + "{\"lat\":39.9,\"lng\":116.3,\"accuracy\":5,\"timestamp\":1000},"
                                + "{\"lat\":39.9,\"lng\":116.301,\"accuracy\":5,\"timestamp\":11000}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(2));
        verify(repository).insertPoints(eq("s1"), anyList());
        verify(coachAnalysisService).maybeAnalyze("s1");
    }

    // ---------- events ----------

    @Test
    void eventsMissingEventFieldReturns400() throws Exception {
        authedPost("/api/v1/events",
                        "{\"events\":[{\"session_id\":\"s1\",\"client_ts\":123}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("event required"));
        verify(repository, never()).insertEvents(anyList());
    }

    @Test
    void eventsSucceed() throws Exception {
        authedPost("/api/v1/events",
                        "{\"events\":[{\"session_id\":\"s1\",\"event\":\"app_start\",\"detail\":\"v1.0\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(1));
        verify(repository).insertEvents(anyList());
    }

    @Test
    void eventsRepositoryFailureReturns500() throws Exception {
        doThrow(new RuntimeException("db down")).when(repository).insertEvents(anyList());
        authedPost("/api/v1/events",
                        "{\"events\":[{\"session_id\":\"s1\",\"event\":\"app_start\"}]}")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("db down"));
    }
}
