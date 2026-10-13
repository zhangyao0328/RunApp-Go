#!/bin/bash
# RunPulse Server 构建脚本（Spring Boot）

set -e

echo "🔨 构建 RunPulse Server..."

# 本机默认 java 可能是 Java 8，构建必须显式使用 JDK 17。
if [ -x /usr/libexec/java_home ]; then
    export JAVA_HOME=$(/usr/libexec/java_home -v 17)
else
    if ! java -version 2>&1 | grep -q 'version "17\.'; then
        echo "❌ 未找到 Java 17，请先安装 JDK 17"
        exit 1
    fi
fi
export PATH="$JAVA_HOME/bin:$PATH"

echo "☕ 使用 Java: $($JAVA_HOME/bin/java -version 2>&1 | head -n 1)"

# 先构建管理后台前端（产物拷贝到 src/main/resources/static/admin，打进 jar）
"$(dirname "$0")/../../frontend/admin-web/build.sh"

# 清理并构建（包含测试）
mvn clean package

echo "✅ 构建完成！"
ls -lh target/runpulse-server.jar
