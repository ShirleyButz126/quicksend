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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

/**
 * HotspotHelper: Manage Android WiFi hotspot for QuickSend (no-WiFi file transfer).
 *
 * Strategy:
 *   - Android 10+ (API 29+): startLocalOnlyHotspot() — local-only, no cellular data
 *   - Android 9- (API 24-28): jump to system hotspot settings page
 */
public class HotspotHelper {

    private static final String TAG = "QuickSend-Hotspot";

    private final Context context;
    private final WifiManager wifiManager;
    private LocalOnlyHotspotReservation hotspotReservation;
    private volatile boolean starting = false;

    // Static holder: MainActivity sets this in onCreate, HotspotHelper calls it on state change
    public static volatile HotspotStateListener stateListener;

    public interface HotspotStateListener {
        void onHotspotStateChanged(String state, String lanIp);
    }

    public HotspotHelper(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
    }

    // === 对外 API ===

    /** @return "ok", "starting", "redirect:<settings_action>", "error:..." or "permission_needed:..." */
    public synchronized String startHotspot() {
        // Permission checks (runtime for Android 13+)
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            return "permission_needed:NEARBY_WIFI_DEVICES";
        }
        // Location permission required for WiFi ops on Android 12 and below
        if (Build.VERSION.SDK_INT >= 24 && Build.VERSION.SDK_INT < 33
                && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return "permission_needed:ACCESS_FINE_LOCATION";
        }

        if (Build.VERSION.SDK_INT >= 29) {
            return startLocalOnlyHotspot();
        } else {
            // Android 9 and below: can't programmatically enable hotspot
            return "redirect:" + Settings.ACTION_WIFI_AP_SETTINGS;
        }
    }

    public synchronized String stopHotspot() {
        if (Build.VERSION.SDK_INT >= 29 && hotspotReservation != null) {
            try {
                hotspotReservation.close();
                hotspotReservation = null;
                notifyState("stopped");
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

    /** Find LAN IP of the active interface (hotspot or WiFi) */
    public String getLanIp() {
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface netint : Collections.list(nets)) {
                if (!netint.isUp() || netint.isLoopback()) continue;
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
        // WifiManager fallback
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
            wifiManager.startLocalOnlyHotspot(new LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(LocalOnlyHotspotReservation reservation) {
                    super.onStarted(reservation);
                    hotspotReservation = reservation;
                    starting = false;
                    Log.i(TAG, "LocalOnlyHotspot started");
                    notifyState("started");
                }

                @Override
                public void onStopped() {
                    super.onStopped();
                    hotspotReservation = null;
                    starting = false;
                    Log.i(TAG, "LocalOnlyHotspot stopped");
                    notifyState("stopped");
                }

                @Override
                public void onFailed(int reason) {
                    super.onFailed(reason);
                    starting = false;
                    Log.w(TAG, "LocalOnlyHotspot failed: reason=" + reason);
                    String reasonText;
                    switch (reason) {
                        case LocalOnlyHotspotCallback.ERROR_NO_CHANNEL:
                            reasonText = "no_channel"; break;
                        case 1: // ERROR_REDIRECTION_NOT_ENABLED (API 30+, value=1)
                            reasonText = "redirection_not_enabled"; break;
                        case 2: // ERROR_SYSTEM_FEATURE_NOT_SUPPORTED (API 30+, value=2)
                            reasonText = "not_supported"; break;
                        default:
                            reasonText = "unknown(" + reason + ")";
                    }
                    notifyState("failed:" + reasonText);
                }
            }, null);
            return "starting";
        } catch (SecurityException e) {
            starting = false;
            Log.e(TAG, "startLocalOnlyHotspot SecurityException: " + e.getMessage());
            return "permission_needed:NEARBY_WIFI_DEVICES";
        } catch (Exception e) {
            starting = false;
            Log.e(TAG, "startLocalOnlyHotspot failed: " + e.getMessage());
            return "error:" + e.getMessage();
        }
    }

    private void notifyState(String state) {
        if (stateListener != null) {
            stateListener.onHotspotStateChanged(state, getLanIp());
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
