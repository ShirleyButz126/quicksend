#!/data/data/com.termux/files/usr/bin/bash
# 查看 QuickSend 运行状态
PID_FILE="$HOME/.quicksend.pid"

echo ""
echo "=== QuickSend 状态 ==="

RUNNING=false
if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE" 2>/dev/null)
    if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
        echo "运行中 ✓  (PID $PID)"
        RUNNING=true
    else
        echo "PID 文件存在但进程已死，清理中..."
        rm -f "$PID_FILE"
    fi
fi

if [ "$RUNNING" = false ]; then
    # 兜底检查
    PIDS=$(pgrep -f "python.*app.py" 2>/dev/null || true)
    if [ -n "$PIDS" ]; then
        echo "运行中 ✓  (进程 $PIDS)"
        RUNNING=true
    else
        echo "未运行 ✗"
    fi
fi

echo ""
echo "=== 网络 ==="
if command -v ip &>/dev/null; then
    ip addr 2>/dev/null | grep -oP 'inet \K[\d.]+' | grep -v '^127\.'
fi

echo ""
echo "=== 日志尾部 (最近 10 行) ==="
if [ -f "$HOME/.quicksend.log" ]; then
    tail -10 "$HOME/.quicksend.log" 2>/dev/null
else
    echo "(无日志)"
fi
echo ""
