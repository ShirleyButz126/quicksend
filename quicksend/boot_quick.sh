#!/data/data/com.termux/files/usr/bin/bash
# 开机自启脚本
# 由 install_termux.sh 自动复制到 ~/.termux-boot/boot/
SCRIPT_DIR="$(dirname "$0")"
# 注意：boot 目录里的路径可能不是原脚本目录，
# 所以这里用 find 定位 app.py
APP_DIR=$(find /data/data/com.termux/files/home -name "app.py" -path "*/quicksend/*" -not -path "*/build/*" 2>/dev/null | head -1 | xargs dirname 2>/dev/null)

if [ -z "$APP_DIR" ]; then
    APP_DIR="/data/data/com.termux/files/home/quicksend"
fi

if [ -f "$APP_DIR/start_quick.sh" ]; then
    nohup bash "$APP_DIR/start_quick.sh" >/dev/null 2>&1 &
fi
