[app]

# === 应用基本信息 ===
title = QuickSend
package.name = quicksend
package.domain = org.quicksend
version = 1.0.3

# === 源码 ===
source.dir = .
source.include_exts = py,png,jpg,jpeg,kv,atlas,json,html,js,css,ico,svg,woff2,woff,ttf,otf
source.exclude_dirs = .git, __pycache__, node_modules, uploads, QuickSend, _internal
# 注意：不去掉 build/dist —— 避免把 static/dist（前端编译产物）也排除了
source.exclude_patterns = *.spec,*.bat,*.sh,*.md,*.ps1,*.txt,*.log

# === APK 入口和依赖 ===
requirements = python3,kivy==2.3.0,flask,werkzeug,pillow
mainclass = main.py

# === 界面 ===
orientation = portrait
fullscreen = 0

# === Android 权限（Flask 局域网服务 + 后台常驻） ===
android.permissions = INTERNET,ACCESS_NETWORK_STATE,ACCESS_WIFI_STATE,WAKE_LOCK,FOREGROUND_SERVICE,FOREGROUND_SERVICE_SPECIAL_USE

# === Android API ===
android.api = 33
android.minapi = 24
android.archs = arm64-v8a,armeabi-v7a
android.allow_backup = True

# 保持应用在前台（Android 会给较高优先级）
# 如果要后台常驻，需要 ForegroundService，留给后续版本

# === Buildozer ===
[buildozer]
log_level = 2
warn_on_root = 1
