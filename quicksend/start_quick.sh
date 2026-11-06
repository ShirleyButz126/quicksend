#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
# QuickSend 启动脚本
# 后台运行 Flask 服务，阻止系统休眠杀进程
# ============================================================
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

PID_FILE="$HOME/.quicksend.pid"
LOG_FILE="$HOME/.quicksend.log"

# 如果已在跑，先停掉
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE" 2>/dev/null)
    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
        echo "QuickSend 已在运行 (PID $OLD_PID)，先停止..."
        kill "$OLD_PID" 2>/dev/null
        sleep 1
    fi
    rm -f "$PID_FILE"
fi

# 1. 获取 Termux 唤醒锁（防止休眠杀进程）
if command -v termux-wake-lock &>/dev/null; then
    termux-wake-lock
fi

# 2. 后台启动 Flask（stdout/stderr 写入日志）
export HEADLESS=1
export PYTHONUNBUFFERED=1
nohup python app.py >> "$LOG_FILE" 2>&1 &
APP_PID=$!
echo "$APP_PID" > "$PID_FILE"

# 3. 等一下让端口起来
sleep 3

# 4. 打印状态
echo ""
echo "=========================================="
echo " QuickSend 已启动"
echo " PID: $APP_PID"
echo " 日志: $LOG_FILE"
echo "=========================================="
echo ""

# 5. 显示局域网 IP
if command -v ip &>/dev/null; then
    WIFI_IP=$(ip addr 2>/dev/null | grep -oP 'inet \K[\d.]+' | grep -v '^127\.' | head -1)
    if [ -n "$WIFI_IP" ]; then
        echo " 手机 IP:  http://$WIFI_IP:5000"
    fi
fi
echo " 本机:    http://127.0.0.1:5000"
echo ""
echo " 浏览器打开上面的地址即可"
echo " 停止服务: bash stop_quick.sh"

# 6. 发个 Android 通知（如果有 termux-api）
if command -v termux-notification &>/dev/null; then
    termux-notification \
        --title "QuickSend 已启动" \
        --content "局域网文件传输服务运行中" \
        --id quicksend \
        --priority high 2>/dev/null || true
fi
