#!/usr/bin/env python3
"""
Pydroid 3 专用启动脚本
直接运行本文件即可（自动 headless 模式）
"""
import os, sys

# 强制 headless
os.environ['HEADLESS'] = '1'

# 切到脚本所在目录
HERE = os.path.dirname(os.path.abspath(__file__))
os.chdir(HERE)
sys.path.insert(0, HERE)

print("=" * 50)
print(" QuickSend — Android / Pydroid 3 模式")
print("=" * 50)

# 直接执行 app.py 的 main
exec(open('app.py', encoding='utf-8').read())
