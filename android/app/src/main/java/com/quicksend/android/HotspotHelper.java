package com.quicksend.android;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.LocalOnlyHotspotCallback;
import android.net.wifi.WifiManager.LocalOnlyHotspotReservation;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

/**
 * HotspotHelper: Manage Android WiFi hotspot for QuickSend (no-WiFi file transfer).
 *
 * Strategy:
 *   - Android 10+ (API 29+): startLocalOnlyHotspot() if supported
 *   - Fallback: jump to system hotspot settings page
 */
public class HotspotHelper {

    private static final String TAG = "QuickSend-Hotspot";

    private final Context context;
    private final WifiManager wifiManager;
    private LocalOnlyHotspotReservation hotspotReservation;
    private volatile boolean starting = false;
    // LocalOnlyHotspot 的 SSID/密码是系统随机生成的，必须回传给前端展示
    private volatile String hotspotSsid = "";
    private volatile String hotspotPassword = "";

    // Static holder: MainActivity sets this in onCreate
    public static volatile HotspotStateListener stateListener;

    public interface HotspotStateListener {
        void onHotspotStateChanged(String state, String lanIp);
    }

    public HotspotHelper(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
    }

    // === 对外 API ===

    /** @return "ok", "starting", "fallback", "error:..." or "permission_needed:..." */
    public synchronized String startHotspot() {
        // Permission checks
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            return "permission_needed:NEARBY_WIFI_DEVICES";
        }
        if (Build.VERSION.SDK_INT >= 24 && Build.VERSION.SDK_INT < 33
                && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return "permission_needed:ACCESS_FINE_LOCATION";
        }

        if (Build.VERSION.SDK_INT >= 29) {
            String result = startLocalOnlyHotspot();
            // 已经在运行 / 正在启动 → 直接返回，不要误报失败
            if (result.startsWith("starting") || result.startsWith("ok")) {
                return result;
            }
            // 保留真实失败原因，交给上层展示，不要再覆盖成 unknown
            Log.w(TAG, "startLocalOnlyHotspot returned: " + result);
            return result.startsWith("error:") ? result : "error:" + result;
        }
        return "error:not_supported_below_android10";
    }

    public synchronized String stopHotspot() {
        if (Build.VERSION.SDK_INT >= 29 && hotspotReservation != null) {
            try {
                hotspotReservation.close();
                hotspotReservation = null;
                starting = false;
                notifyState("stopped");
                return "ok";
            } catch (Exception e) {
                Log.w(TAG, "stop failed: " + e.getMessage());
                return "error:" + e.getMessage();
            }
        }
        return "fallback";
    }

    public synchronized boolean isStarting() { return starting; }

    public String getHotspotSsid() { return hotspotSsid; }

    public String getHotspotPassword() { return hotspotPassword; }

    public boolean isHotspotActive() { return hotspotReservation != null; }

    /**
     * 只返回热点网卡（ap0 / swlan0 / wlan1 等）的私有地址。
     * 之前直接取"第一个非 loopback 地址"，WiFi 关闭时会拿到流量网卡地址（运营商 IP）。
     */
    public String getHotspotIp() {
        try {
            String fallback = null;
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface netint : Collections.list(nets)) {
                if (!netint.isUp() || netint.isLoopback()) continue;
                String name = netint.getName().toLowerCase();
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("wwan")
                        || name.startsWith("pdp") || name.startsWith("clat") || name.startsWith("tun")
                        || name.startsWith("dummy") || name.startsWith("p2p") || name.startsWith("ppp")) {
                    continue;  // 流量 / VPN 网卡，直接排除
                }
                boolean apLike = name.startsWith("ap") || name.contains("softap")
                        || name.startsWith("swlan") || name.startsWith("wlan1") || name.startsWith("wlan2");
                for (InetAddress addr : Collections.list(netint.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (!isPrivateIpv4(ip)) continue;
                    if (apLike) return ip;
                    if (fallback == null) fallback = ip;
                }
            }
            return fallback;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isPrivateIpv4(String ip) {
        if (ip.startsWith("192.168.") || ip.startsWith("10.")) return true;
        if (ip.startsWith("172.")) {
            try {
                int second = Integer.parseInt(ip.split("\\.")[1]);
                return second >= 16 && second <= 31;
            } catch (Exception ignored) { }
        }
        return false;
    }

    /** Find LAN IP of the active interface (prefer hotspot AP interface) */
    public String getLanIp() {
        if (hotspotReservation != null) {
            String apIp = getHotspotIp();
            if (apIp != null) return apIp;
        }
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface netint : Collections.list(nets)) {
                if (!netint.isUp() || netint.isLoopback()) continue;
                String name = netint.getName().toLowerCase();
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("wwan")
                        || name.startsWith("pdp") || name.startsWith("clat") || name.startsWith("tun")
                        || name.startsWith("dummy") || name.startsWith("ppp")) {
                    continue;  // 排除流量 / VPN 网卡，避免把运营商 IP 当成本机地址
                }
                Enumeration<InetAddress> addrs = netint.getInetAddresses();
                for (InetAddress addr : Collections.list(addrs)) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (isPrivateIpv4(ip)) return ip;
                    }
                }
            }
        } catch (Exception ignored) { }
        try {
            if (wifiManager != null && wifiManager.getConnectionInfo() != null) {
                int ipInt = wifiManager.getConnectionInfo().getIpAddress();
                if (ipInt != 0) {
                    return String.format("%d.%d.%d.%d",
                            ipInt & 0xff, (ipInt >> 8) & 0xff, (ipInt >> 16) & 0xff, (ipInt >> 24) & 0xff);
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    // === Android 10+ Local Only Hotspot ===

    private String startLocalOnlyHotspot() {
        Log.i(TAG, "startLocalOnlyHotspot called");
        if (starting) {
            Log.i(TAG, "already starting");
            return "starting";
        }
        if (hotspotReservation != null) {
            String ip = getLanIp();
            Log.i(TAG, "already reserved, ip=" + ip);
            return "ok:" + (ip != null ? ip : "");
        }
        if (wifiManager == null) {
            Log.e(TAG, "wifiManager is null!");
            return "error:no_wifi_manager";
        }

        starting = true;
        try {
            wifiManager.startLocalOnlyHotspot(new LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(LocalOnlyHotspotReservation reservation) {
                    super.onStarted(reservation);
                    handleHotspotStarted(reservation);
                }

                @Override
                public void onStopped() {
                    super.onStopped();
                    hotspotReservation = null;
                    starting = false;
                    hotspotSsid = "";
                    hotspotPassword = "";
                    Log.i(TAG, "LocalOnlyHotspot stopped");
                    notifyState("stopped");
                }

                @Override
                public void onFailed(int reason) {
                    super.onFailed(reason);
                    starting = false;
                    Log.e(TAG, "LocalOnlyHotspot FAILED! reason=" + reason + " (" + reasonText(reason) + ")");
                    notifyState("fallback:" + reasonText(reason) + (isWifiConnected() ? "|wifi_on" : "|wifi_off"));
                }
            }, null);
            Log.i(TAG, "startLocalOnlyHotspot() called — waiting for callback...");

            // === 12 秒超时保护：部分机型 LOH 回包很慢，5 秒会误判 ===
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (starting) {
                    Log.w(TAG, "LocalOnlyHotspot timeout (12s) — no callback received, forcing fallback");
                    starting = false;
                    notifyState("fallback:timeout" + (isWifiConnected() ? "|wifi_on" : "|wifi_off"));
                }
            }, 12000);

            return "starting";
        } catch (SecurityException e) {
            starting = false;
            Log.e(TAG, "SecurityException: " + e.getMessage());
            return "error:security:" + e.getMessage();
        } catch (IllegalStateException e) {
            starting = false;
            Log.e(TAG, "IllegalStateException (WiFi disabled?): " + e.getMessage());
            // Android 10+ 无法用代码开关 Wi-Fi，先尝试（旧系统有效），再重试一次
            try {
                if (Build.VERSION.SDK_INT <= 30) {
                    wifiManager.setWifiEnabled(true);
                    Log.i(TAG, "WiFi re-enabled, retrying startLocalOnlyHotspot...");
                    starting = true;
                    wifiManager.startLocalOnlyHotspot(new LocalOnlyHotspotCallback() {
                        @Override
                        public void onStarted(LocalOnlyHotspotReservation reservation) {
                            super.onStarted(reservation);
                            Log.i(TAG, "Retry: LocalOnlyHotspot started!");
                            handleHotspotStarted(reservation);
                        }
                        @Override
                        public void onStopped() {
                            super.onStopped();
                            hotspotReservation = null;
                            starting = false;
                            notifyState("stopped");
                        }
                        @Override
                        public void onFailed(int reason) {
                            super.onFailed(reason);
                            starting = false;
                            Log.e(TAG, "Retry also failed: reason=" + reason + " (" + reasonText(reason) + ")");
                            notifyState("fallback:retry_failed:" + reasonText(reason)
                                    + (isWifiConnected() ? "|wifi_on" : "|wifi_off"));
                        }
                    }, null);
                    return "starting";
                }
            } catch (Exception retryEx) {
                starting = false;
                Log.e(TAG, "Retry exception: " + retryEx.getMessage());
                return "error:retry_" + retryEx.getClass().getSimpleName() + ":" + retryEx.getMessage();
            }
            return "error:illegal_state:" + e.getMessage();
        } catch (Exception e) {
            starting = false;
            Log.e(TAG, "startLocalOnlyHotspot exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return "error:" + e.getClass().getSimpleName() + ":" + e.getMessage();
        }
    }

    /** 热点真正起来后：读随机 SSID/密码 + 轮询等 AP 网卡拿到私网地址 */
    private void handleHotspotStarted(LocalOnlyHotspotReservation reservation) {
        hotspotReservation = reservation;
        starting = false;
        try {
            if (Build.VERSION.SDK_INT >= 30 && reservation.getWifiConfiguration() != null) {
                android.net.wifi.WifiConfiguration cfg = reservation.getWifiConfiguration();
                hotspotSsid = cfg.SSID != null ? cfg.SSID.replace("\"", "") : "";
                hotspotPassword = cfg.preSharedKey != null ? cfg.preSharedKey.replace("\"", "") : "";
            }
        } catch (Throwable t) {
            Log.w(TAG, "Read WifiConfiguration failed: " + t.getMessage());
        }
        Log.i(TAG, "LocalOnlyHotspot started! ssid=" + hotspotSsid + ", waiting for AP ip...");
        verifyHotspotStarted(0);
    }

    private void verifyHotspotStarted(final int attempt) {
        String ip = getHotspotIp();
        if (ip != null) {
            Log.i(TAG, "Hotspot AP ip=" + ip);
            notifyState("started", ip);
            return;
        }
        if (attempt < 8) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> verifyHotspotStarted(attempt + 1), 500);
            return;
        }
        Log.w(TAG, "Hotspot started but AP interface has no IPv4 address");
        notifyState("started", null);
    }

    private void notifyState(String state) {
        notifyState(state, getLanIp());
    }

    private void notifyState(String state, String ip) {
        Log.i(TAG, "notifyState: " + state + " ip=" + ip);
        if (stateListener != null) {
            stateListener.onHotspotStateChanged(state, ip);
        }
    }

    /** LocalOnlyHotspot 失败原因码 → 可读标识 */
    private static String reasonText(int reason) {
        switch (reason) {
            case WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL: return "no_channel";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE: return "incompatible_mode";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED: return "tethering_disallowed";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC: return "generic";
            default: return "unknown(" + reason + ")";
        }
    }

    /** 当前是否已连接 Wi-Fi（部分机型连接 Wi-Fi 时无法开热点） */
    public boolean isWifiConnected() {
        try {
            android.net.wifi.WifiInfo info = wifiManager != null ? wifiManager.getConnectionInfo() : null;
            return info != null && info.getNetworkId() != -1;
        } catch (Exception e) {
            return false;
        }
    }

    // === Permission helpers ===

    public boolean needsNearbyWifiPermission() {
        return Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED;
    }

    public boolean needsLocationPermission() {
        return Build.VERSION.SDK_INT >= 24 && Build.VERSION.SDK_INT < 33
                && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED;
    }
}
