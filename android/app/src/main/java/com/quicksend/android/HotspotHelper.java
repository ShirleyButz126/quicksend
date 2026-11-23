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
            // If LOH is starting, we'll get callback. If it returns "fallback" or error, jump to settings
            if (result.startsWith("starting")) {
                return result;
            }
            // startLocalOnlyHotspot returned an immediate error — fallback to settings
            Log.w(TAG, "startLocalOnlyHotspot returned: " + result + " — falling back to system settings");
            return "fallback";
        }
        return "fallback";
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
                    hotspotReservation = reservation;
                    starting = false;
                    String ip = getLanIp();
                    Log.i(TAG, "LocalOnlyHotspot started! ip=" + ip);
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
                    // reason: 0=NO_CHANNEL, 1=REDIRECTION_NOT_ENABLED, 2=SYSTEM_FEATURE_NOT_SUPPORTED
                    String reasonText;
                    switch (reason) {
                        case 0: reasonText = "no_channel"; break;
                        case 1: reasonText = "redirection_not_enabled"; break;
                        case 2: reasonText = "not_supported"; break;
                        default: reasonText = "unknown(" + reason + ")";
                    }
                    Log.e(TAG, "LocalOnlyHotspot FAILED! reason=" + reason + " (" + reasonText + ")");
                    // Fallback to system settings
                    notifyState("fallback:" + reasonText);
                }
            }, null);
            Log.i(TAG, "startLocalOnlyHotspot() called — waiting for callback...");

            // === 5 秒超时保护：某些设备 LOH 永远不回调 ===
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (starting) {
                    Log.w(TAG, "LocalOnlyHotspot timeout (5s) — no callback received, forcing fallback");
                    starting = false;
                    notifyState("fallback:timeout");
                }
            }, 5000);

            return "starting";
        } catch (SecurityException e) {
            starting = false;
            Log.e(TAG, "SecurityException: " + e.getMessage());
            return "permission_needed:NEARBY_WIFI_DEVICES";
        } catch (IllegalStateException e) {
            starting = false;
            Log.e(TAG, "IllegalStateException (WiFi disabled?): " + e.getMessage());
            // Try to enable WiFi first and retry once
            try {
                if (Build.VERSION.SDK_INT <= 30) {
                    wifiManager.setWifiEnabled(true);
                    Log.i(TAG, "WiFi re-enabled, retrying startLocalOnlyHotspot...");
                    starting = true;
                    wifiManager.startLocalOnlyHotspot(new LocalOnlyHotspotCallback() {
                        @Override
                        public void onStarted(LocalOnlyHotspotReservation reservation) {
                            super.onStarted(reservation);
                            hotspotReservation = reservation;
                            starting = false;
                            Log.i(TAG, "Retry: LocalOnlyHotspot started!");
                            notifyState("started");
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
                            Log.e(TAG, "Retry also failed: reason=" + reason);
                            notifyState("fallback:retry_failed");
                        }
                    }, null);
                    return "starting";
                }
            } catch (Exception retryEx) {
                Log.e(TAG, "Retry exception: " + retryEx.getMessage());
            }
            notifyState("fallback:" + e.getMessage());
            return "fallback";
        } catch (Exception e) {
            starting = false;
            Log.e(TAG, "startLocalOnlyHotspot exception: " + e.getMessage());
            notifyState("fallback:" + e.getMessage());
            return "fallback";
        }
    }

    private void notifyState(String state) {
        Log.i(TAG, "notifyState: " + state + " ip=" + getLanIp());
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
