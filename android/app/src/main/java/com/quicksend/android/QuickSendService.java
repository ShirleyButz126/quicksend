package com.quicksend.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class QuickSendService extends Service {

    public static final int PORT = 5000;
    private static final String TAG = "QuickSend";
    private static final String CHANNEL_ID = "quicksend";
    private static final int NOTIFICATION_ID = 1;

    private volatile boolean started = false;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuickSend:server");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat(buildNotification("QuickSend 服务启动中…"));
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }
        if (!started) {
            started = true;
            new Thread(this::startPython, "quicksend-python").start();
        }
        return START_STICKY;
    }

    private void startPython() {
        try {
            File dataDir = getExternalFilesDir(null);
            if (dataDir == null) {
                dataDir = getFilesDir();
            }
            File staticDir = new File(getFilesDir(), "static");
            extractAssets(staticDir);

            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(getApplicationContext()));
            }
            Python.getInstance()
                    .getModule("quicksend_android")
                    .callAttr("start", dataDir.getAbsolutePath(), staticDir.getAbsolutePath(), PORT);

            updateNotification("QuickSend 运行中 · 端口 " + PORT);
        } catch (Throwable t) {
            Log.e(TAG, "Python server failed", t);
            updateNotification("服务启动失败：" + t.getMessage());
        }
    }

    private void extractAssets(File dest) throws IOException {
        File marker = new File(dest, ".extracted");
        if (marker.exists()) {
            return;
        }
        deleteRecursively(dest);
        dest.mkdirs();
        copyAsset("static", dest);
        marker.createNewFile();
    }

    private void copyAsset(String assetPath, File dest) throws IOException {
        String[] children = getAssets().list(assetPath);
        if (children == null || children.length == 0) {
            File parent = dest.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try (InputStream in = getAssets().open(assetPath);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
            return;
        }
        dest.mkdirs();
        for (String child : children) {
            copyAsset(assetPath + "/" + child, new File(dest, child));
        }
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "QuickSend 服务", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持局域网文件传输服务运行");
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("QuickSend")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
