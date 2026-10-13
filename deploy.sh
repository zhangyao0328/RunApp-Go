#!/bin/bash
# RunPulse Server 部署脚本（Spring Boot fat jar）

set -e

SERVER="root@47.116.205.225"
REMOTE_DIR="/opt/runpulse/server"
LOCAL_JAR="target/runpulse-server.jar"
SERVICE_NAME="runpulse-server"
HEALTH_URL="http://127.0.0.1:18080/healthz"

echo "🚀 部署 RunPulse Server..."

if [ ! -f "$LOCAL_JAR" ]; then
    echo "❌ 未找到 $LOCAL_JAR，请先运行 ./build.sh"
    exit 1
fi

echo "☕ 检查服务器 Java 版本..."
if ! ssh "$SERVER" 'java -version 2>&1 | grep -q "version \"17\."'; then
    echo "❌ 服务器未安装 Java 17，无法运行 Spring Boot 服务"
    exit 1
fi

echo "🗄️  检查 MySQL 和 gva 数据库..."
if ! ssh "$SERVER" 'bash -s' <<'ENDDBCHECK'
set -euo pipefail
UNIT="/etc/systemd/system/runpulse-server.service"
if ! systemctl is-active --quiet mysql; then
    echo "MySQL 服务未运行" >&2
    exit 1
fi
DB_HOST=$(sed -n 's/^Environment="RUNPULSE_DB_HOST=\(.*\)"$/\1/p' "$UNIT")
DB_PORT=$(sed -n 's/^Environment="RUNPULSE_DB_PORT=\(.*\)"$/\1/p' "$UNIT")
DB_USER=$(sed -n 's/^Environment="RUNPULSE_DB_USER=\(.*\)"$/\1/p' "$UNIT")
DB_PASSWORD=$(sed -n 's/^Environment="RUNPULSE_DB_PASSWORD=\(.*\)"$/\1/p' "$UNIT")
if ! MYSQL_PWD="$DB_PASSWORD" mysql -h"${DB_HOST:-127.0.0.1}" -P"${DB_PORT:-3306}" -u"${DB_USER:-root}" -N \
        -e "SHOW DATABASES" 2>/dev/null | grep -qx "gva"; then
    echo "gva 数据库不存在或无法连接" >&2
    exit 1
fi
ENDDBCHECK
then
    echo "❌ 生产 MySQL/gva 数据库不可用；为避免切换后服务循环重启，本次部署已中止"
    exit 1
fi

echo "📤 上传 jar 到服务器..."
scp "$LOCAL_JAR" "$SERVER:/tmp/runpulse-server.jar.new"

echo "🔧 远程部署并执行健康检查..."
if ! ssh "$SERVER" 'bash -s' <<'ENDSSH'
set -euo pipefail

SERVICE="runpulse-server"
UNIT="/etc/systemd/system/runpulse-server.service"
DIR="/opt/runpulse/server"
BACKUP_DIR="/opt/runpulse/backups"
HEALTH_URL="http://127.0.0.1:18080/healthz"
TS=$(date +%Y%m%d_%H%M%S)

mkdir -p "$DIR" "$BACKUP_DIR"

# 记录部署前状态，失败回滚时尽量恢复到原状态，避免遗留禁用/启用错误。
NEW_WAS_ENABLED=$(systemctl is-enabled "$SERVICE" 2>/dev/null || true)
LEGACY_SERVICE="runpulse.service"
LEGACY_WAS_ENABLED=$(systemctl is-enabled "$LEGACY_SERVICE" 2>/dev/null || true)
LEGACY_WAS_ACTIVE=inactive
if systemctl is-active --quiet "$LEGACY_SERVICE"; then
    LEGACY_WAS_ACTIVE=active
fi

rollback() {
    trap - ERR
    echo "⚠️  部署失败，开始回滚..."
    systemctl stop "$SERVICE" 2>/dev/null || true

    latest_go=$(ls -1t "$BACKUP_DIR"/runpulse-server.go.* 2>/dev/null | head -n 1 || true)
    latest_jar=$(ls -1t "$BACKUP_DIR"/runpulse-server.jar.* 2>/dev/null | head -n 1 || true)
    latest_unit=$(ls -1t "$BACKUP_DIR"/runpulse-server.service.* 2>/dev/null | head -n 1 || true)

    if [ -n "$latest_go" ]; then
        cp "$latest_go" "$DIR/runpulse-server"
        chmod +x "$DIR/runpulse-server"
    fi
    if [ -n "$latest_jar" ]; then
        cp "$latest_jar" "$DIR/runpulse-server.jar"
        chmod 644 "$DIR/runpulse-server.jar"
    fi
    if [ -n "$latest_unit" ]; then
        cp "$latest_unit" "$UNIT"
    fi

    if [ "$NEW_WAS_ENABLED" = "enabled" ] || [ "$NEW_WAS_ENABLED" = "enabled-runtime" ]; then
        systemctl enable "$SERVICE" 2>/dev/null || true
    else
        systemctl disable "$SERVICE" 2>/dev/null || true
    fi
    systemctl daemon-reload

    if [ "$LEGACY_WAS_ACTIVE" = "active" ]; then
        if [ "$LEGACY_WAS_ENABLED" = "enabled" ] || [ "$LEGACY_WAS_ENABLED" = "enabled-runtime" ]; then
            systemctl enable "$LEGACY_SERVICE" 2>/dev/null || true
        fi
        systemctl start "$LEGACY_SERVICE" || true
    else
        systemctl start "$SERVICE" || true
    fi
    systemctl status "$SERVICE" --no-pager -l || true
    echo "❌ 已回滚到上一可用版本"
    exit 1
}

trap rollback ERR

if [ "$LEGACY_WAS_ACTIVE" = "active" ]; then
    echo "检测到旧 SQLite $LEGACY_SERVICE，部署前将其停止并禁用（保留 /root/run 数据）..."
    systemctl stop "$LEGACY_SERVICE"
    systemctl disable "$LEGACY_SERVICE"
fi

echo "停止旧服务..."
systemctl stop "$SERVICE" 2>/dev/null || true

echo "备份旧版本..."
if [ -f "$DIR/runpulse-server" ]; then
    cp "$DIR/runpulse-server" "$BACKUP_DIR/runpulse-server.go.$TS"
fi
if [ -f "$DIR/runpulse-server.jar" ]; then
    cp "$DIR/runpulse-server.jar" "$BACKUP_DIR/runpulse-server.jar.$TS"
fi
if [ -f "$UNIT" ]; then
    cp "$UNIT" "$BACKUP_DIR/runpulse-server.service.$TS"
fi

if [ ! -f "$UNIT" ]; then
    echo "未找到 $UNIT，无法保留现有环境变量并切换 ExecStart"
    exit 1
fi

echo "安装新版本 jar..."
mv /tmp/runpulse-server.jar.new "$DIR/runpulse-server.jar"
chmod 644 "$DIR/runpulse-server.jar"

echo "更新 systemd ExecStart（保留现有 Environment 配置）..."
sed -i "s|^ExecStart=.*|ExecStart=/usr/bin/java -jar $DIR/runpulse-server.jar|" "$UNIT"
systemctl daemon-reload
systemctl enable "$SERVICE"

echo "启动服务..."
systemctl start "$SERVICE"

echo "等待健康检查..."
for i in $(seq 1 30); do
    MAIN_PID=$(systemctl show -p MainPID --value "$SERVICE" 2>/dev/null || echo 0)
    MAIN_CMD=$(ps -p "$MAIN_PID" -o args= 2>/dev/null || true)
    if curl -fsS "$HEALTH_URL" >/dev/null 2>&1 \
            && systemctl is-active --quiet "$SERVICE" \
            && grep -Fq "/usr/bin/java -jar $DIR/runpulse-server.jar" <<<"$MAIN_CMD"; then
        echo "✅ 健康检查通过: $HEALTH_URL"
        systemctl status "$SERVICE" --no-pager -l
        trap - ERR
        exit 0
    fi
    sleep 1
done

echo "健康检查超时: $HEALTH_URL"
exit 1
ENDSSH
then
    echo "❌ 远程部署失败"
    exit 1
fi

echo "✅ RunPulse Server 部署完成！"
echo "📊 查看日志: ssh $SERVER 'tail -f /var/log/gva/runpulse.log'"
