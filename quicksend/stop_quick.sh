#!/data/data/com.termux/files/usr/bin/bash
# 停止 QuickSend 后台服务
PID_FILE="$HOME/.quicksend.pid"

if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE" 2>/dev/null)
    if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
        kill "$PID" 2>/dev/null
        sleep 1
        if kill -0 "$PID" 2>/dev/null; then
            kill -9 "$PID" 2>/dev/null
        fi
        echo "QuickSend 已停止 (PID $PID)"
    else
        echo "QuickSend 未运行"
    fi
    rm -f "$PID_FILE"
else
    # 兜底：按进程名杀
    PIDS=$(pgrep -f "python.*app.py" 2>/dev/null || true)
    if [ -n "$PIDS" ]; then
        echo "未找到 PID 文件，直接杀进程: $PIDS"
        kill $PIDS 2>/dev/null
        sleep 1
        kill -9 $PIDS 2>/dev/null
    else
        echo "QuickSend 未运行"
    fi
fi

# 释放唤醒锁
if command -v termux-wake-unlock &>/dev/null; then
    termux-wake-unlock 2>/dev/null || true
fi

# 移除通知
if command -v termux-notification-remove &>/dev/null; then
    termux-notification-remove quicksend 2>/dev/null || true
fi
