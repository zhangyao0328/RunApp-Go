# runpulse-server

运动轨迹采集 + 云端 AI 实时分析服务（1.0）。

单二进制 + SQLite（WAL），零外部依赖，扔到任意 VPS 即可运行。

## 构建与运行

```bash
# 构建（部署到 Linux 服务器时交叉编译）
CGO_ENABLED=0 go build -o runpulse-server .
# Linux amd64:
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o runpulse-server-linux .

# 运行
RUNPULSE_API_KEY=你的密钥 \
OPENCLAW_BASE_URL=http://127.0.0.1:18789/v1 \
OPENCLAW_API_KEY=openclaw的key \
OPENCLAW_MODEL=你的模型名 \
./runpulse-server
```

> 注意：本地 macOS 构建必须加 `CGO_ENABLED=0`（本机 CLT 会导致默认构建的二进制缺 LC_UUID 无法启动）。
> 依赖下载走国内镜像：`GOPROXY=https://goproxy.cn,direct go mod tidy`

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `RUNPULSE_ADDR` | `:8080` | 监听地址 |
| `RUNPULSE_API_KEY` | （空=不校验，仅调试用） | App 请求头 `X-API-Key` |
| `RUNPULSE_DB` | `runpulse.db` | SQLite 路径 |
| `OPENCLAW_BASE_URL` | （空=关闭 AI） | OpenClaw OpenAI 兼容接口地址 |
| `OPENCLAW_API_KEY` | 空 | OpenClaw 鉴权 |
| `OPENCLAW_MODEL` | 空 | 模型名 |
| `ANALYSIS_MIN_POINTS` | `30` | 攒够多少个新轨迹点触发一次分析 |
| `ANALYSIS_MIN_INTERVAL_SEC` | `180` | 两次分析的最小间隔（秒） |

## API（全部需要 `X-API-Key` 头）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/healthz` | 健康检查（免鉴权） |
| POST | `/api/v1/sessions/start` | 开始运动 `{session_id, sport_type, start_time}`，幂等 |
| POST | `/api/v1/points/batch` | 批量上传轨迹点 `{session_id, points[]}`，单批 ≤500 |
| POST | `/api/v1/sessions/end` | 结束运动 `{session_id, end_time}` |
| GET | `/api/v1/sessions/{id}/analysis?since_id=N` | 增量拉取 AI 点评 `{messages:[{id,timestamp,text}]}` |

`sport_type`：`run` / `trail` / `bike`。

## 设计要点

- **AI 分析为异步触发**：上传接口落库后立即返回，分析在后台 goroutine 执行（per-session 去重），App 通过轮询 `analysis` 接口获取结果。
- **分析节流**：攒够 `ANALYSIS_MIN_POINTS` 个点且距上次超过 `ANALYSIS_MIN_INTERVAL_SEC` 才调 LLM，控制成本。
- **给 AI 的是摘要不是原始点**：距离（haversine，含漂移过滤）、时长、平均/最近配速、平均心率、爬升，省 token 且更聚焦。
- **教练提示词**约束输出 ≤60 字纯文本，App 端直接 TTS 播报。
- 已结束的 session 不再触发实时分析（训练后深度报告留待后续版本）。

## 后续 TODO

- 训练后完整报告（session end 时触发一次长分析）
- FIT/GPX 导出（对接 `../data/analyze_fit.py`）
- 多用户鉴权（JWT）与 Postgres 迁移
# RunApp-Go
