#!/bin/bash

echo "======================================"
echo "      福州麻将 - 启动脚本"
echo "======================================"
echo ""

# 设置 Maven 路径
if [ -f "$HOME/maven/bin/mvn" ]; then
    MVN="$HOME/maven/bin/mvn"
    echo "✅ 使用本地 Maven: $HOME/maven"
elif command -v mvn &> /dev/null; then
    MVN="mvn"
    echo "✅ 使用系统 Maven"
else
    echo "❌ Maven 未安装"
    echo ""
    echo "请先安装 Maven："
    echo "  macOS: brew install maven"
    echo "  或访问: https://maven.apache.org/download.cgi"
    exit 1
fi

echo ""

# 检查 Java 版本
echo "Java 版本："
java -version
echo ""

# 进入后端目录编译并运行
BACKEND_DIR="$(cd "$(dirname "$0")" && pwd)/backend"
if [ ! -f "$BACKEND_DIR/pom.xml" ]; then
    echo "❌ 未找到 backend/pom.xml"
    exit 1
fi

echo "正在编译项目 (backend)..."
(cd "$BACKEND_DIR" && $MVN clean compile)

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ 编译成功！"
    echo ""
    echo "正在启动服务器..."
    echo "访问: http://localhost:8080"
    echo ""
    (cd "$BACKEND_DIR" && $MVN spring-boot:run)
else
    echo ""
    echo "❌ 编译失败，请检查错误信息"
    exit 1
fi
