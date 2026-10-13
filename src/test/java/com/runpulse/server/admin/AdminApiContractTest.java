package com.runpulse.server.admin;

import com.runpulse.server.config.AdminProperties;
import com.runpulse.server.config.RunpulseProperties;
import com.runpulse.server.controller.admin.AuthController;
import com.runpulse.server.controller.admin.DashboardController;
import com.runpulse.server.controller.admin.RoleController;
import com.runpulse.server.controller.admin.SessionAdminController;
import com.runpulse.server.controller.admin.UserController;
import com.runpulse.server.repository.AdminRepository;
import com.runpulse.server.repository.RunPulseRepository;
import com.runpulse.server.security.JwtService;
import com.runpulse.server.service.TrackSummaryService;
import io.jsonwebtoken.Claims;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理后台 API 契约测试：@WebMvcTest + MockMvc + mocked 持久层，不触真实数据库。
 * JWT 过滤器用真实 Filter + mock JwtService 覆盖登录态/未登录两种路径。
 */
@WebMvcTest({AuthController.class, UserController.class, RoleController.class,
        SessionAdminController.class, DashboardController.class})
@Import(BCryptPasswordEncoder.class)
class AdminApiContractTest {

    private static final String TOKEN = "test.jwt.token";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminRepository adminRepository;

    @MockBean
    private RunPulseRepository runPulseRepository;

    @MockBean
    private TrackSummaryService summaryService;

    @MockBean
    private com.runpulse.server.service.TrackFileService trackFileService;

    @MockBean
    private JwtService jwtService;

    /** ApiKeyFilter 是注册的 Filter Bean，需 mock 其依赖；/admin 路径实际不经过 API Key 校验。 */
    @MockBean
    private RunpulseProperties props;

    /** JwtService 已 mock，AdminProperties 仅为潜在依赖兜底。 */
    @MockBean
    private AdminProperties adminProperties;

    @BeforeEach
    void setUp() {
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(claims.get("uid")).thenReturn(1);
        when(claims.getSubject()).thenReturn("admin");
        when(jwtService.parse(TOKEN)).thenReturn(claims);
        when(jwtService.parse(anyString())).thenAnswer(inv ->
                TOKEN.equals(inv.getArgument(0)) ? claims : null);
        when(jwtService.issue(eq(1L), eq("admin"))).thenReturn(TOKEN);
    }

    // ---------- 鉴权 ----------

    @Test
    void adminApiWithoutTokenReturns401Envelope() throws Exception {
        mockMvc.perform(get("/admin/api/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").exists());
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        var authUser = new AdminRepository.AuthUser(1L, "admin", "$2a$10$hash", 1L);
        when(adminRepository.findForAuth("admin")).thenReturn(Optional.of(authUser));

        mockMvc.perform(post("/admin/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void loginSuccessReturnsToken() throws Exception {
        // 用真实 BCryptPasswordEncoder 编码，走真实密码校验路径
        String hash = new BCryptPasswordEncoder().encode("888");
        var authUser = new AdminRepository.AuthUser(1L, "admin", hash, 1L);
        when(adminRepository.findForAuth("admin")).thenReturn(Optional.of(authUser));

        mockMvc.perform(post("/admin/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"888\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").value(TOKEN));
    }

    @Test
    void disabledUserCannotLogin() throws Exception {
        String hash = new BCryptPasswordEncoder().encode("x123456");
        var authUser = new AdminRepository.AuthUser(2L, "jack", hash, 2L);
        when(adminRepository.findForAuth("jack")).thenReturn(Optional.of(authUser));

        mockMvc.perform(post("/admin/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"jack\",\"password\":\"x123456\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("账号已被禁用"));
    }

    // ---------- 用户 ----------

    @Test
    void userListRequiresTokenAndReturnsEnvelope() throws Exception {
        var user = new AdminRepository.AdminUser(1L, "uuid", "admin", "超级管理员",
                "17611111111", "admin@example.com", 1L, List.of(888L));
        when(adminRepository.userPage(anyInt(), anyInt(), any())).thenReturn(List.of(user));
        when(adminRepository.countUsers(any())).thenReturn(1L);

        mockMvc.perform(get("/admin/api/users").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].username").value("admin"));
    }

    @Test
    void createUserRejectsDuplicate() throws Exception {
        when(adminRepository.usernameExists("tom")).thenReturn(true);

        mockMvc.perform(post("/admin/api/users")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"tom\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.msg").value("用户名已存在"));
    }

    // ---------- 角色 / 记录 / 仪表盘 ----------

    @Test
    void roleListWorks() throws Exception {
        when(adminRepository.listRoles()).thenReturn(List.of(
                new AdminRepository.AdminRole(888L, "超级管理员", 0L)));

        mockMvc.perform(get("/admin/api/roles").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].authorityId").value(888));
    }

    @Test
    void sessionDetailNotFound() throws Exception {
        when(runPulseRepository.findSessionRow("nope")).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/api/sessions/nope").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    /** 回归：前端默认会带 sportType= 空串参数，必须按 null 处理，否则列表恒为空。 */
    @Test
    void sessionListTreatsBlankSportTypeAsAbsent() throws Exception {
        var row = new RunPulseRepository.SessionRow("s-1", "run", 1L, 2L, 3L, 4L, null);
        when(runPulseRepository.sessionPage(eq(0), eq(10), isNull(), isNull())).thenReturn(List.of(row));
        when(runPulseRepository.countSessions(isNull(), isNull())).thenReturn(1L);

        mockMvc.perform(get("/admin/api/sessions")
                        .param("sportType", "")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void dashboardAggregates() throws Exception {
        when(adminRepository.countActiveUsers()).thenReturn(1L);
        when(runPulseRepository.countSessions(null, null)).thenReturn(8L);
        when(runPulseRepository.countPoints()).thenReturn(5220L);
        when(runPulseRepository.countMessages()).thenReturn(34L);
        when(runPulseRepository.sessionPage(eq(0), anyInt(), eq(null), eq(null))).thenReturn(List.of());
        when(runPulseRepository.countSessionsSince(any(Long.class))).thenReturn(0L);
        when(runPulseRepository.sessionCountsPerDay(any(Long.class))).thenReturn(List.of());
        when(runPulseRepository.sportTypeCounts()).thenReturn(List.of());

        mockMvc.perform(get("/admin/api/dashboard").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sessionCount").value(8))
                .andExpect(jsonPath("$.data.pointCount").value(5220))
                .andExpect(jsonPath("$.data.trend").isArray());
    }
}
