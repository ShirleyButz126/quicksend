#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
# QuickSend 安装脚本 —— 仅需跑一次
# 在 Termux 里执行: bash install_termux.sh
# ============================================================
set -e

echo ""
echo "=========================================="
echo " QuickSend Termux 安装"
echo "=========================================="

# 1. 更新包列表 & 装 Python
echo ""
echo "[1/4] 安装 Python ..."
pkg update -y >/dev/null 2>&1 || true
pkg install -y python >/dev/null 2>&1 || pkg install python

# 2. 装 Flask
echo "[2/4] 安装 Flask ..."
pip install flask werkzeug 2>/dev/null || pip install flask

# 3. 可选：装 Termux API（用于发通知、保持唤醒）
echo "[3/4] 安装 Termux API 工具 ..."
pkg install -y termux-api 2>/dev/null || true

# 4. 创建必要目录
echo "[4/4] 创建目录 ..."
mkdir -p ~/.termux-boot/boot

# 自我注册开机自启
cp -f boot_quick.sh ~/.termux-boot/boot/99-quicksend.sh 2>/dev/null || true
chmod +x ~/.termux-boot/boot/99-quicksend.sh 2>/dev/null || true

echo ""
echo "=========================================="
echo " 安装完成！"
echo " 启动服务: bash start_quick.sh"
echo " 停止服务: bash stop_quick.sh"
echo " 状态:     bash status_quick.sh"
echo ""
echo " 手机浏览器访问 http://<手机IP>:5000"
echo "=========================================="
