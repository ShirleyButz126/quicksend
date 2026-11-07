import os, socket

def _detect_lan_ip():
    """在 Android 上检测真实 LAN IP（不依赖外部网络可达性）"""
    try:
        # 方法 1: UDP socket connect 到 8.8.8.8（Android 上可能被防火墙拦）
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(0.5)
        try:
            s.connect(("8.8.8.8", 53))
            ip = s.getsockname()[0]
            s.close()
            if ip and not ip.startswith('127.') and not ip.startswith('169.254.'):
                return ip
        except Exception:
            pass
        finally:
            try: s.close()
            except: pass

        # 方法 2: 遍历网络接口找第一个非 loopback IPv4
        try:
            import netifaces
            for iface in netifaces.interfaces():
                addrs = netifaces.ifaddresses(iface)
                for addr in addrs.get(netifaces.AF_INET, []):
                    ip = addr.get('addr', '')
                    if ip and not ip.startswith('127.') and ip != '0.0.0.0':
                        return ip
        except ImportError:
            pass
        except Exception:
            pass

        # 方法 3: Linux /sys 接口枚举（Android 也是 Linux）
        try:
            import subprocess
            out = subprocess.check_output(['ip', '-4', 'addr', 'show'], stderr=subprocess.DEVNULL, timeout=2)
            for line in out.decode().split('\n'):
                if 'inet ' in line:
                    ip = line.split('inet ')[1].split('/')[0].strip()
                    if ip and not ip.startswith('127.') and ip != '0.0.0.0':
                        return ip
        except Exception:
            pass

        # 方法 4: hostname -I
        try:
            import subprocess
            out = subprocess.check_output(['hostname', '-I'], stderr=subprocess.DEVNULL, timeout=2)
            for ip in out.decode().split():
                if ip and not ip.startswith('127.') and '.' in ip:
                    return ip.strip()
        except Exception:
            pass

        return None
    except Exception:
        return None

def start(data_dir, static_dir, port):
    os.environ['QUICKSEND_DATA_ROOT'] = data_dir
    os.environ['QUICKSEND_STATIC_DIR'] = static_dir
    os.environ['PORT'] = str(port)
    os.environ['HEADLESS'] = '1'
    os.environ['QUICKSEND_ANDROID'] = '1'

    # === 关键：在 Android 上注入真实 LAN IP ===
    # 后端 get_local_ip() 在 Android 上可能因为防火墙/权限检测失败
    # 这里提前检测好注入环境变量，后端 /api/ip 会优先用它
    lan_ip = _detect_lan_ip()
    if lan_ip:
        os.environ['HOST_IP'] = lan_ip
        os.environ['LAN_IP'] = lan_ip
        print(f'[Android] 检测到 LAN IP: {lan_ip}')
    else:
        print('[Android] 未检测到 LAN IP，后端将 fallback 到其他方式')

    import app as quicksend_app
    quicksend_app.GLOBAL_PORT = int(port)
    quicksend_app.app.run(
        host='0.0.0.0',
        port=int(port),
        debug=False,
        threaded=True,
        use_reloader=False,
    )
