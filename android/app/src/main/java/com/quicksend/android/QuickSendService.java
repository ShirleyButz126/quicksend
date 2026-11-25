package com.quicksend.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class QuickSendService extends Service {

    public static final int PORT = 5000;
    public static final String ACTION_SHARE_UPLOAD = "com.quicksend.android.action.SHARE_UPLOAD";
    public static final String ACTION_SHARE_DONE = "com.quicksend.android.action.SHARE_UPLOAD_DONE";
    public static final String EXTRA_URIS = "share_uris";
    private static final String TAG = "QuickSend";
    private static final String CHANNEL_ID = "quicksend";
    private static final int NOTIFICATION_ID = 1;
    private static final int SHARE_NOTIFICATION_ID = 2;

    private volatile boolean started = false;
    private volatile boolean shareRunning = false;
    private long lastShareNotifyAt = 0L;
    private final List<Uri> pendingShareUris = Collections.synchronizedList(new ArrayList<>());
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
        if (intent != null && ACTION_SHARE_UPLOAD.equals(intent.getAction())) {
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra(EXTRA_URIS);
            if (uris != null && !uris.isEmpty()) {
                enqueueShareUploads(uris);
            }
        }
        return START_STICKY;
    }

    private void enqueueShareUploads(List<Uri> uris) {
        pendingShareUris.addAll(uris);
        startShareWorkerIfIdle();
    }

    private void startShareWorkerIfIdle() {
        synchronized (this) {
            if (shareRunning) return;
            shareRunning = true;
        }
        new Thread(this::drainShareUploads, "quicksend-share-upload").start();
    }

    private void drainShareUploads() {
        List<Uri> batch = new ArrayList<>();
        try {
            synchronized (pendingShareUris) {
                batch.addAll(pendingShareUris);
                pendingShareUris.clear();
            }
            if (batch.isEmpty()) return;
            if (!waitForServerReady(90000L)) {
                notifyShareDone(0, batch.size(), "服务未就绪，请稍后重试");
                return;
            }
            final int count = batch.size();
            final int[] current = {0};
            ShareUploadClient client = new ShareUploadClient();
            ShareUploadClient.Result result = client.uploadAll(
                    getApplicationContext(), batch, new ShareUploadClient.Listener() {
                        @Override
                        public void onFileStart(String name, long size, int index, int fileCount) {
                            current[0] = index + 1;
                            publishShareProgress("正在导入 " + current[0] + "/" + fileCount + "：" + name, -1, true);
                        }

                        @Override
                        public void onFileProgress(String name, long uploaded, long total) {
                            int percent = total > 0 ? (int) Math.min(100, uploaded * 100 / total) : -1;
                            String text = "正在导入 " + current[0] + "/" + count + "：" + name
                                    + " · " + (uploaded / 1024 / 1024) + "MB";
                            publishShareProgress(text, percent, false);
                        }

                        @Override
                        public void onFileUploaded(String name) {
                        }

                        @Override
                        public void onFileFailed(String name, String reason) {
                        }
                    });
            notifyShareDone(result.ok, result.failed, result.errors.isEmpty() ? null : result.errors.get(0));
        } catch (Throwable t) {
            Log.e(TAG, "分享导入失败", t);
            notifyShareDone(0, batch.size(), String.valueOf(t.getMessage()));
        } finally {
            synchronized (this) {
                shareRunning = false;
            }
            synchronized (pendingShareUris) {
                if (!pendingShareUris.isEmpty()) {
                    // 上传过程中又有新的分享进来，继续处理
                    startShareWorkerIfIdle();
                }
            }
        }
    }

    private boolean waitForServerReady(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.Socket socket = new java.net.Socket();
                try {
                    socket.connect(new java.net.InetSocketAddress("127.0.0.1", PORT), 800);
                } finally {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                        new java.net.URL("http://127.0.0.1:" + PORT + "/api/ip").openConnection();
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1000);
                int code = conn.getResponseCode();
                if (code >= 200 && code < 500) return true;
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private Notification.Builder newBuilder(String text) {
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
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(pendingIntent);
    }

    private void publishShareProgress(String text, int percent, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastShareNotifyAt < 500) return;
        lastShareNotifyAt = now;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        Notification.Builder builder = newBuilder(text).setOngoing(true);
        if (percent >= 0) {
            builder.setProgress(100, percent, false);
        } else {
            builder.setProgress(0, 0, true);
        }
        manager.notify(SHARE_NOTIFICATION_ID, builder.build());
    }

    private void notifyShareDone(int ok, int failed, String firstError) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            String text = "分享导入完成：成功 " + ok + (failed > 0 ? "，失败 " + failed : "");
            manager.notify(SHARE_NOTIFICATION_ID,
                    newBuilder(text).setOngoing(false).setAutoCancel(true).build());
        }
        Intent done = new Intent(ACTION_SHARE_DONE);
        done.setPackage(getPackageName());
        done.putExtra("ok", ok);
        done.putExtra("failed", failed);
        if (firstError != null) done.putExtra("error", firstError);
        sendBroadcast(done);
    }

    private void startPython() {
        try {
            // 清除上一次的错误日志
            File errLog = new File(getFilesDir(), "python_error.log");
            if (errLog.exists()) errLog.delete();

            File dataDir = getExternalFilesDir(null);
            if (dataDir == null) {
                dataDir = getFilesDir();
            }
            File staticDir = new File(getFilesDir(), "static");
            extractAssets(staticDir);

            Log.i(TAG, "Python dirs: data=" + dataDir + " static=" + staticDir);
            Log.i(TAG, "Python started=" + Python.isStarted());

            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(getApplicationContext()));
            }
            Log.i(TAG, "Python instance got, loading quicksend_android...");
            Python.getInstance()
                    .getModule("quicksend_android")
                    .callAttr("start", dataDir.getAbsolutePath(), staticDir.getAbsolutePath(), PORT);

            updateNotification("QuickSend 运行中 · 端口 " + PORT);
        } catch (Throwable t) {
            Log.e(TAG, "Python server failed", t);
            // 把完整栈追踪写到文件，Activity 读取显示给用户
            try {
                File errLog = new File(getFilesDir(), "python_error.log");
                StringBuilder sb = new StringBuilder();
                sb.append("Error: ").append(t.toString()).append('\n');
                sb.append("\n--- Stack trace ---\n");
                for (StackTraceElement ste : t.getStackTrace()) {
                    sb.append("  at ").append(ste).append('\n');
                }
                Throwable cause = t.getCause();
                if (cause != null) {
                    sb.append("\n--- Cause ---\n");
                    sb.append(cause.toString()).append('\n');
                    for (StackTraceElement ste : cause.getStackTrace()) {
                        sb.append("  at ").append(ste).append('\n');
                    }
                }
                try (FileOutputStream fos = new FileOutputStream(errLog)) {
                    fos.write(sb.toString().getBytes("UTF-8"));
                }
                Log.e(TAG, "Error log written to " + errLog.getAbsolutePath());
            } catch (Exception ignored) {}
            updateNotification("服务启动失败：" + t.getMessage());
        }
    }

    private void extractAssets(File dest) throws IOException {
        // 用版本号做 marker，每次构建都重新解压（避免覆盖安装时用旧资源）
        int myBuild = BuildConfig.BUILD_NUMBER;
        File marker = new File(dest, ".extracted_" + myBuild);
        if (marker.exists()) {
            Log.i(TAG, "Assets already extracted for build " + myBuild);
            return;
        }
        Log.i(TAG, "Extracting assets for build " + myBuild + " to " + dest);
        deleteRecursively(dest);
        dest.mkdirs();
        copyAsset("static", dest);
        marker.createNewFile();
        // 清理旧版本 marker
        java.io.File[] oldMarkers = dest.listFiles((d, name) -> name.startsWith(".extracted_") && !name.equals(marker.getName()));
        if (oldMarkers != null) {
            for (java.io.File m : oldMarkers) m.delete();
        }
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
