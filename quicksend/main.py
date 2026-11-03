#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
QuickSend APK 入口 —— Android 专用
Kivy 极简界面 + Flask 后台服务

打包: buildozer android debug deploy run
"""
import os
import sys
import threading
import time
import socket
import webbrowser

# 强制 Android 模式（避免 app.py 里的 webbrowser 等干扰）
os.environ['HEADLESS'] = '1'
os.chdir(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.getcwd())

# 延迟导入 Kivy（打包环境里 Kivy 是 requirements 的一部分）
from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.scrollview import ScrollView
from kivy.uix.gridlayout import GridLayout
from kivy.clock import Clock
from kivy.graphics import Color, Rectangle
from kivy.core.window import Window

# Flask 全局
_flask_thread = None
_flask_port = 5000
_app_module = None
_LOG_LINES = []

def log(msg):
    ts = time.strftime('%H:%M:%S')
    line = f'[{ts}] {msg}'
    _LOG_LINES.append(line)
    if len(_LOG_LINES) > 200:
        _LOG_LINES.pop(0)
    print(line)

def get_wifi_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(0.3)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip if not ip.startswith('127.') else None
    except Exception:
        return None

def start_flask_service():
    """启动 Flask 后台线程"""
    global _flask_thread, _app_module, _flask_port
    if _flask_thread and _flask_thread.is_alive():
        return True
    try:
        # 动态加载 app.py（app.py 已内置 Android 检测）
        import importlib.util
        spec = importlib.util.spec_from_file_location("quicksend_app",
                                                      os.path.join(os.path.dirname(os.path.abspath(__file__)), "app.py"))
        _app_module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(_app_module)
        
        # 启动 Flask
        def _run():
            try:
                _app_module.app.run(host='0.0.0.0', port=_flask_port,
                                    debug=False, threaded=True, use_reloader=False)
            except Exception as e:
                log(f'Flask 崩溃: {e}')
        
        _flask_thread = threading.Thread(target=_run, daemon=True)
        _flask_thread.start()
        log(f'Flask 启动中 (端口 {_flask_port})...')
        return True
    except Exception as e:
        log(f'启动失败: {e}')
        return False

def stop_flask_service():
    """尝试关闭（Flask 没有优雅关闭 API，直接忽略让进程退出）"""
    global _flask_thread, _app_module
    log('停止服务...')
    _flask_thread = None
    _app_module = None

class QuickSendApp(App):
    """Kivy 主界面"""
    
    def build(self):
        self.title = 'QuickSend'
        Window.size = (360, 600)
        
        root = BoxLayout(orientation='vertical', padding=12, spacing=8)
        
        # 标题
        title = Label(text='QuickSend', font_size=32, size_hint_y=None, height=50, bold=True)
        root.add_widget(title)
        
        # IP 信息卡片
        self.info_label = Label(text='点击下方按钮启动服务', font_size=14,
                                size_hint_y=None, height=120,
                                text_size=(340, None), valign='top')
        root.add_widget(self.info_label)
        
        # 按钮区
        btn_row = BoxLayout(size_hint_y=None, height=60, spacing=10)
        
        self.start_btn = Button(text='▶ 启动', background_color=(0.2, 0.7, 0.2, 1))
        self.start_btn.bind(on_press=self.on_start)
        btn_row.add_widget(self.start_btn)
        
        self.stop_btn = Button(text='■ 停止', background_color=(0.8, 0.2, 0.2, 1), disabled=True)
        self.stop_btn.bind(on_press=self.on_stop)
        btn_row.add_widget(self.stop_btn)
        
        self.open_btn = Button(text='🌐 打开浏览器', background_color=(0.2, 0.4, 0.9, 1), disabled=True)
        self.open_btn.bind(on_press=self.on_open)
        btn_row.add_widget(self.open_btn)
        
        root.add_widget(btn_row)
        
        # 日志区
        log_title = Label(text='运行日志', font_size=12, size_hint_y=None, height=20, bold=True)
        root.add_widget(log_title)
        
        scroll = ScrollView()
        self.log_label = Label(text='(等待启动)', font_size=11, text_size=(340, None),
                               size_hint_y=None, height=300, valign='top')
        scroll.add_widget(self.log_label)
        root.add_widget(scroll)
        
        # 定时刷新
        Clock.schedule_interval(self.refresh_ui, 1.0)
        
        return root
    
    def on_start(self, btn):
        if start_flask_service():
            btn.disabled = True
            self.stop_btn.disabled = False
            self.open_btn.disabled = False
    
    def on_stop(self, btn):
        stop_flask_service()
        btn.disabled = True
        self.start_btn.disabled = False
        self.open_btn.disabled = True
        self.info_label.text = '服务已停止'
    
    def on_open(self, btn):
        url = f'http://127.0.0.1:{_flask_port}'
        try:
            import webbrowser as wb
            wb.open(url)
        except Exception:
            pass
        # Android 原生方式
        try:
            from jnius import autoclass
            Intent = autoclass('android.content.Intent')
            Uri = autoclass('android.net.Uri')
            activity = autoclass('org.kivy.android.PythonActivity').mActivity
            intent = Intent(Intent.ACTION_VIEW)
            intent.setData(Uri.parse(url))
            activity.startActivity(intent)
        except Exception as e:
            log(f'打开浏览器失败: {e}')
    
    def refresh_ui(self, dt):
        # 刷新日志
        self.log_label.text = '\n'.join(_LOG_LINES[-30:])
        
        # 刷新 IP 信息
        if _flask_thread and _flask_thread.is_alive():
            ip = get_wifi_ip() or '127.0.0.1'
            self.info_label.text = (
                f'✅ 运行中\n\n'
                f'本机访问: http://127.0.0.1:{_flask_port}\n'
                f'局域网:   http://{ip}:{_flask_port}\n'
                f'\n电脑浏览器打开"局域网"地址即可互传文件'
            )
        else:
            self.info_label.text = '服务未启动'

if __name__ == '__main__':
    QuickSendApp().run()
