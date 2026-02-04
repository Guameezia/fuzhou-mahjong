#!/bin/bash

# 异地联机：把本机 8080 暴露到公网，朋友通过链接即可加入
# 使用前请先在另一个终端运行 ./start.sh 启动游戏后端

echo "======================================"
echo "  福州麻将 - 异地联机（内网穿透）"
echo "======================================"
echo ""

# 检查后端是否已在运行
if ! curl -s -o /dev/null -w "%{http_code}" --connect-timeout 2 http://localhost:8080 > /dev/null 2>&1; then
    echo "❌ 本机 8080 端口未检测到游戏后端。"
    echo ""
    echo "请先在另一个终端运行以下命令启动游戏："
    echo "  ./start.sh"
    echo ""
    echo "看到 \"Started MahjongApplication\" 后，再运行本脚本："
    echo "  ./start-remote.sh"
    echo ""
    exit 1
fi

echo "✅ 已检测到本机游戏后端 (http://localhost:8080)"
echo ""

# 优先使用 cloudflared（免登录、稳定）
if command -v cloudflared &> /dev/null; then
    echo "正在使用 Cloudflare Tunnel 暴露到公网..."
    echo "请把下面出现的 https 链接发给朋友，对方用浏览器打开即可联机。"
    echo ""
    # 默认使用 http2（TCP）以避免部分网络环境下 QUIC（UDP）超时
    # 如需手动切换，可在运行前设置：CLOUDFLARED_PROTOCOL=quic ./start-remote.sh
    CLOUDFLARED_PROTOCOL="${CLOUDFLARED_PROTOCOL:-http2}"
    cloudflared tunnel --url http://localhost:8080 --protocol "$CLOUDFLARED_PROTOCOL"
    exit 0
fi

# 其次尝试 ngrok
if command -v ngrok &> /dev/null; then
    echo "正在使用 ngrok 暴露到公网..."
    echo "请把下面 Forwarding 的 https 地址发给朋友。"
    echo ""
    ngrok http 8080
    exit 0
fi

# 都没有则提示手动方式
echo "未检测到 cloudflared 或 ngrok。请任选一种方式暴露 8080 端口："
echo ""
echo "1) Cloudflare Tunnel（推荐，免登录）："
echo "   brew install cloudflared"
echo "   cloudflared tunnel --url http://localhost:8080 --protocol http2"
echo ""
echo "2) ngrok："
echo "   安装并登录 https://ngrok.com 后执行："
echo "   ngrok http 8080"
echo ""
echo "3) Localtunnel（需已安装 Node）："
echo "   npx localtunnel --port 8080"
echo ""
echo "更多说明见: REMOTE_PLAY.md"
exit 1
