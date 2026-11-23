package com.quicksend.android;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.LocalOnlyHotspotCallback;
import android.net.wifi.WifiManager.LocalOnlyHotspotReservation;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

/**
 * HotspotHelper: Manage Android WiFi hotspot for QuickSend (no-WiFi file transfer).
 *
 * Strategy:
 *   - Android 10+ (API 29+): startLocalOnlyHotspot() — no cellular data needed
 *   - Android 9- (API 24-28): jump to system hotspot settings page (can't programmatically enable without system privileges)
 */
public class HotspotHelper {

    private static final String TAG = "QuickSend-Hotspot";

    private final Context context;
    private final WifiManager wifiManager;
    private LocalOnlyHotspotReservation hotspotReservation;
    private volatile boolean starting = false;

    public HotspotHelper(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
    }

    // === 对外 API ===

    /** @return "ok" if started (or starting), "redirect://..." to jump to settings on old Android, or error message */
    public synchronized String startHotspot() {
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+ needs NEARBY_WIFI_DEVICES runtime permission
            if (context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                return "permission_needed:NEARBY_WIFI_DEVICES";
            }
        }
        if (Build.VERSION.SDK_INT >= 10) {
            return startLocalOnlyHotspot();
        } else {
            // Old Android: jump to settings
            return "redirect:" + Settings.ACTION_WIFI_AP_SETTINGS;
        }
    }

    public synchronized String stopHotspot() {
        if (Build.VERSION.SDK_INT >= 10 && hotspotReservation != null) {
            try {
                hotspotReservation.close();
                hotspotReservation = null;
                return "ok";
            } catch (Exception e) {
                Log.w(TAG, "stop failed: " + e.getMessage());
                return "error:" + e.getMessage();
            }
        }
        // Old Android: jump to settings
        return "redirect:" + Settings.ACTION_WIFI_AP_SETTINGS;
    }

    public synchronized boolean isStarting() { return starting; }

    /** Best effort: find the LAN IP of the device's active hotspot/interface */
    public String getLanIp() {
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface netint : Collections.list(nets)) {
                if (!netint.isUp() || netint.isLoopback()) continue;
                // Skip mobile data (rmnet) — we want wlan or rndis or tiwlan
                String name = netint.getName();
                if (name.startsWith("rmnet") || name.startsWith("ppp")) continue;
                Enumeration<InetAddress> addrs = netint.getInetAddresses();
                for (InetAddress addr : Collections.list(addrs)) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        // Fallback: WifiManager
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
        if (starting) return "starting";
        if (hotspotReservation != null) {
            return "ok:" + getLanIp();
        }
        if (wifiManager == null) return "error:no_wifi_manager";

        starting = true;
        try {
            // If WiFi is on, it may auto-disconnect when LocalOnlyHotspot starts. That's fine.
            wifiManager.startLocalOnlyHotspot(new LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(LocalOnlyHotspotReservation reservation) {
                    super.onStarted(reservation);
                    hotspotReservation = reservation;
                    starting = false;
                    Log.i(TAG, "LocalOnlyHotspot started");
                    // Fire callback to JS bridge via MainActivity
                    notifyHotspotState("started");
                }

                @Override
                public void onStopped() {
                    super.onStopped();
                    hotspotReservation = null;
                    starting = false;
                    Log.i(TAG, "LocalOnlyHotspot stopped");
                    notifyHotspotState("stopped");
                }

                @Override
                public void onFailed(int reason) {
                    super.onFailed(reason);
                    starting = false;
                    Log.w(TAG, "LocalOnlyHotspot failed: reason=" + reason);
                    String reasonText;
                    switch (reason) {
                        case LocalOnlyHotspotCallback.ERROR_NO_CHANNEL: reasonText = "no_channel"; break;
                        case LocalOnlyHotspotCallback.ERROR_REDIRECTION_NOT_ENABLED: reasonText = "redirection_not_enabled"; break;
                        case LocalOnlyHotspotCallback.ERROR_SYSTEM_FEATURE_NOT_SUPPORTED: reasonText = "not_supported"; break;
                        default: reasonText = "unknown(" + reason + ")";
                    }
                    notifyHotspotState("failed:" + reasonText);
                }
            }, null);
            return "starting";
        } catch (SecurityException e) {
            starting = false;
            // This can happen if NEARBY_WIFI_DEVICES permission not granted on Android 13+
            Log.e(TAG, "startLocalOnlyHotspot SecurityException: " + e.getMessage());
            return "permission_needed:NEARBY_WIFI_DEVICES";
        } catch (Exception e) {
            starting = false;
            Log.e(TAG, "startLocalOnlyHotspot failed: " + e.getMessage());
            return "error:" + e.getMessage();
        }
    }

    // === Notify JS bridge (runs on main thread via runOnUiThread) ===

    private void notifyHotspotState(final String state) {
        // Find MainActivity via context
        // MainActivity registers itself at setup
        if (HotspotListener.instance != null) {
            HotspotListener.instance.onHotspotStateChanged(state, getLanIp());
        }
    }

    // === Listener interface (MainActivity implements this) ===
    public interface HotspotListener {
        void onHotspotStateChanged(String state, String lanIp);
    }

    // Helper: static instance holder so HotspotHelper can notify MainActivity
    public static class HotspotListener {
        public static volatile HotspotListener instance;
    }

    // === Check if we should request NEARBY_WIFI_DEVICES on Android 13+ ===
    public boolean needsNearbyWifiPermission() {
        return Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED;
    }

    // === Check if location permission (required for WiFi operations on Android 12 and below) ===
    public boolean needsLocationPermission() {
        return Build.VERSION.SDK_INT >= 24
                && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED;
    }
}
