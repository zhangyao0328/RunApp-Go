# RunPulse Server

RunPulse API 后端服务，负责接收 Android App 上传的运动数据，调用 AI 分析，返回教练点评。

> **2026-09-28 已上线生产**：Spring Boot 版已替换旧 Go/SQLite 服务，`runpulse-server.service` 在 :18080 运行（MySQL `gva` 库）。旧 gin-vue-admin 管理后台已整体移除；`deploy.sh` 会在 MySQL/`gva` 不可用时自动中止并回滚。

## 技术栈

- **语言/运行时**: Java 17
- **框架**: Spring Boot 3.3 + Spring MVC + JdbcTemplate
- **构建工具**: Maven
- **数据库**: MySQL 8.0+
- **AI**: OpenClaw（OpenAI 兼容接口）

## 快速开始

### 本地开发

推荐使用项目根目录脚本：

```bash
./scripts/start-local-dev.sh runpulse
```

手动启动：

```bash
# 需要 JDK 17（本机默认 java 可能是 Java 8）
export JAVA_HOME=$(/usr/libexec/java_home -v 17)

# 加载本地配置
set -a
source .env.local
set +a

mvn spring-boot:run
```

### 测试

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn test
```

### 构建

```bash
./build.sh
```

构建产物：`target/runpulse-server.jar`（可执行 fat jar，包含测试）。

### 部署

```bash
./deploy.sh
```

部署脚本会：

1. 检查服务器 Java 17。
2. 检查 MySQL 正在运行且 `gva` 数据库存在；不可用则中止，不停止现有服务。
3. 上传 jar 到 `/opt/runpulse/server/runpulse-server.jar`。
4. 备份旧 Go 二进制、旧 jar 和 systemd unit。
5. 仅替换 systemd `ExecStart`，保留现有数据库/API Key/OpenClaw 环境变量。
6. 重启服务并检查 `http://127.0.0.1:18080/healthz`，同时确认端口由 Java 进程持有。
7. 健康检查失败时自动恢复上一版本和部署前启用状态。

## 管理后台

同一进程内置管理平台（替代 gin-vue-admin）：

- **端口**：`ADMIN_ADDR`（默认 `:8080`；生产 `:18081`，经 nginx :2201 反代；本地 `:8081`）
- **鉴权**：JWT（HS256，12h），`Authorization: Bearer <token>`，登录 `POST /admin/api/auth/login`
- **用户体系**：复用 `gva` 库 `sys_users` / `sys_authorities` / `sys_user_authority`（bcrypt 密码，admin/123456）
- **页面**：仪表盘、运动记录、记录详情、用户管理、角色管理（Vue3 SPA，构建产物托管在 `/admin/`）
- **API 前缀**：`/admin/api/*`，响应信封 `{code:0,data}`；App API（`/api/v1/*`）格式不变
- 前端工程在 `frontend/admin-web/`，`build.sh` 自动构建并拷入 `src/main/resources/static/admin/`

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `RUNPULSE_ADDR` | `:8080` | 监听地址，兼容 `:8080` / `:18080` / `127.0.0.1:8080` |
| `ADMIN_ADDR` | `:8080` | 管理后台监听端口；与主端口相同时共用 |
| `ADMIN_JWT_SECRET` | 开发默认值 | JWT 密钥，**生产必须配置**（如 `openssl rand -hex 32`） |
| `RUNPULSE_API_KEY` | 空 | App 请求头 `X-API-Key`；生产必须配置 |
| `RUNPULSE_DB_HOST` | `127.0.0.1` | MySQL 主机 |
| `RUNPULSE_DB_PORT` | `3306` | MySQL 端口 |
| `RUNPULSE_DB_USER` | `root` | MySQL 用户 |
| `RUNPULSE_DB_PASSWORD` | 空 | MySQL 密码 |
| `RUNPULSE_DB_NAME` | `gva` | MySQL 数据库名 |
| `OPENCLAW_BASE_URL` | 空 | OpenClaw API 地址，如 `http://127.0.0.1:18789/v1` |
| `OPENCLAW_API_KEY` | 空 | OpenClaw API Key |
| `OPENCLAW_MODEL` | 空 | OpenClaw 模型 |
| `ANALYSIS_MIN_POINTS` | `30` | 触发分析的最小新增轨迹点数 |
| `ANALYSIS_MIN_INTERVAL_SEC` | `180` | 两次分析最小间隔（秒） |

## API 端点

除 `/healthz` 外，均需要 `X-API-Key` 请求头。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/healthz` | 健康检查（免鉴权） |
| POST | `/api/v1/sessions/start` | 开始运动（幂等 upsert） |
| POST | `/api/v1/points/batch` | 批量上传轨迹点（单批 ≤500） |
| POST | `/api/v1/sessions/end` | 结束运动 |
| GET | `/api/v1/sessions/{id}/analysis?since_id=` | 增量拉取 AI 点评 |
| POST | `/api/v1/events` | App 诊断事件上报（单批 ≤100） |

响应保持 Android 当前协议：裸 JSON、`messages` 永远返回数组、错误格式为 `{"error":"..."}`。

## 数据库表

服务启动时仅执行幂等 `CREATE TABLE IF NOT EXISTS`，不会重建或修改已有业务数据。

- `rp_sessions` - 运动会话
- `rp_track_points` - 轨迹点
- `rp_coach_messages` - AI 点评消息
- `rp_client_events` - 客户端诊断事件
- `sys_users` / `sys_authorities` / `sys_user_authority` - 管理后台用户/角色（复用 gin-vue-admin 表，无 DDL）

## 旧 Go 实现

旧 Go 代码已归档到：

```text
../../archive/runpulse-server-go/
```

生产服务器仍保留部署前的 Go 二进制备份，可用于紧急回滚。

## 相关文档

- [项目结构](../../docs/PROJECT_STRUCTURE.md)
- [部署文档](../../docs/DEPLOY_SCRIPTS.md)
- [本地开发](../../docs/LOCAL_DEV.md)
