package com.quicksend.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements HotspotHelper.HotspotStateListener {

    private static final int PORT = 5000;
    private static final int REQ_FILE = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;
    private static final int REQ_WRITE_STORAGE = 1003;
    private static final int REQ_NEARBY_WIFI = 1004;
    private static final int REQ_LOCATION = 1005;
    private static final String DOWNLOAD_SUBDIR = "QuickSend";
    private static final String FILEPROVIDER_AUTH = "com.quicksend.android.fileprovider";

    private WebView webView;
    private TextView splash;
    private ValueCallback<Uri[]> fileCallback;

    private long lastDownloadId = -1;
    private String lastDownloadName;
    private String pendingUrl, pendingUserAgent, pendingContentDisposition, pendingMime, pendingName;

    private HotspotHelper hotspotHelper;

    private final Handler networkHandler = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback networkCallback;
    private ConnectivityManager.NetworkCallback lanNetworkCallback;
    private int networkChangeToken = 0;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id != lastDownloadId) return;
            showOpenDownloadDialog(id, lastDownloadName);
        }
    };

    private final BroadcastReceiver shareDoneReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int ok = intent.getIntExtra("ok", 0);
            int failed = intent.getIntExtra("failed", 0);
            String error = intent.getStringExtra("error");
            if (ok > 0 && webView != null) {
                webView.reload();
            }
            String msg = "分享导入完成：成功 " + ok + (failed > 0 ? "，失败 " + failed : "");
            if (failed > 0 && error != null) msg += "\n" + error;
            Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        splash = new TextView(this);
        splash.setText("QuickSend 服务启动中…");
        splash.setTextSize(16);
        splash.setGravity(Gravity.CENTER);
        root.addView(splash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        hotspotHelper = new HotspotHelper(this);
        HotspotHelper.stateListener = this;

        setupWebView();

        IntentFilter downloadFilter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, downloadFilter, Context.RECEIVER_EXPORTED);
            registerReceiver(shareDoneReceiver, new IntentFilter(QuickSendService.ACTION_SHARE_DONE),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, downloadFilter);
            registerReceiver(shareDoneReceiver, new IntentFilter(QuickSendService.ACTION_SHARE_DONE));
        }

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }

        Intent serviceIntent = new Intent(this, QuickSendService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Log.i("QuickSend", "QuickSendService started OK");
        } catch (Exception e) {
            Log.e("QuickSend", "Failed to start QuickSendService", e);
            Toast.makeText(this, "服务启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        waitForServer();
        registerNetworkMonitor();
        handleShareIntent(getIntent());
    }

    /** 监听默认网络切换（流量 <-> Wi-Fi <-> 网线），变化后重算本机地址并通知前端 */
    private void registerNetworkMonitor() {
        if (networkCallback != null) return;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                scheduleNetworkRefresh();
            }

            @Override
            public void onLost(Network network) {
                scheduleNetworkRefresh();
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                scheduleNetworkRefresh();
            }
        };
        try {
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception e) {
            Log.w("QuickSend", "注册网络监听失败: " + e.getMessage());
            networkCallback = null;
        }

        // 只监听默认网络不够：流量没关时连上 Wi-Fi，默认网络可能仍是流量，
        // 这里再单独监听 Wi-Fi / 网线网卡的出现与消失，保证地址能跟着变。
        lanNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                scheduleNetworkRefresh();
            }

            @Override
            public void onLost(Network network) {
                scheduleNetworkRefresh();
            }
        };
        try {
            android.net.NetworkRequest request = new android.net.NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build();
            cm.registerNetworkCallback(request, lanNetworkCallback);
        } catch (Exception e) {
            Log.w("QuickSend", "注册 Wi-Fi 监听失败: " + e.getMessage());
            lanNetworkCallback = null;
        }
    }

    private void unregisterNetworkMonitor() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        if (networkCallback != null) {
            try {
                cm.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
            networkCallback = null;
        }
        if (lanNetworkCallback != null) {
            try {
                cm.unregisterNetworkCallback(lanNetworkCallback);
            } catch (Exception ignored) {
            }
            lanNetworkCallback = null;
        }
    }

    private void scheduleNetworkRefresh() {
        final int token = ++networkChangeToken;
        networkHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (token != networkChangeToken) return;
                new Thread(() -> {
                    publishLanIp();
                    final String ip = hotspotHelper == null ? null : hotspotHelper.getLanIp();
                    runOnUiThread(() -> {
                        if (webView == null) return;
                        webView.evaluateJavascript(
                                "if(window.__quicksend&&window.__quicksend.onNetworkChange){"
                                        + "window.__quicksend.onNetworkChange({ip:'" + jsEscape(ip) + "'});}", null);
                    });
                }, "quicksend-net-change").start();
            }
        }, 800);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    /** 处理系统「分享到」「用其他应用打开」进来的文件，交给前台服务分片上传 */
    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        List<Uri> uris = extractShareUris(intent);
        if (uris.isEmpty()) {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text != null && text.length() > 0) {
                Uri saved = writeSharedTextToCache(text);
                if (saved != null) uris.add(saved);
            }
        }
        // 清空 action，避免 Activity 重建（如旋转屏幕）时重复导入
        intent.setAction(null);
        if (uris.isEmpty()) return;

        Intent serviceIntent = new Intent(this, QuickSendService.class);
        serviceIntent.setAction(QuickSendService.ACTION_SHARE_UPLOAD);
        serviceIntent.putParcelableArrayListExtra(QuickSendService.EXTRA_URIS, new ArrayList<>(uris));
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e("QuickSend", "分享导入启动失败", e);
            Toast.makeText(this, "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "正在导入 " + uris.size() + " 个文件…", Toast.LENGTH_SHORT).show();
    }

    @SuppressWarnings("deprecation")
    private List<Uri> extractShareUris(Intent intent) {
        List<Uri> uris = new ArrayList<>();
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) uris.add(uri);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) {
                for (Uri uri : list) {
                    if (uri != null) uris.add(uri);
                }
            }
        } else if (Intent.ACTION_VIEW.equals(action)) {
            if (intent.getData() != null) uris.add(intent.getData());
        }
        if (uris.isEmpty() && intent.getClipData() != null) {
            ClipData clip = intent.getClipData();
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        }
        return uris;
    }

    private Uri writeSharedTextToCache(CharSequence text) {
        try {
            File dir = new File(getCacheDir(), "shared");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File file = new File(dir, "分享文本_" + System.currentTimeMillis() + ".txt");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                out.write(text.toString().getBytes("UTF-8"));
            }
            return Uri.fromFile(file);
        } catch (Exception e) {
            Log.e("QuickSend", "保存分享文本失败", e);
            return null;
        }
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);           // 允许 file:// 访问
        settings.setAllowContentAccess(true);        // 允许 content:// 访问 (FileProvider 需要)
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(false);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && url.startsWith("quicksend://open")) {
                    handleNativeOpen(url);
                    return true;
                }
                if (url != null && url.startsWith("quicksend://hotspot")) {
                    handleHotspotRequest(url);
                    return true;
                }
                if (url != null && url.startsWith("quicksend://request-permission")) {
                    handlePermissionRequest(url);
                    return true;
                }
                return false;  // 返回 false 让 WebView 自己加载 http/https
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                Log.e("QuickSend", "WebView error: code=" + errorCode + " desc=" + description + " url=" + failingUrl);
                // 只对主框架错误做提示
                if (failingUrl != null && failingUrl.startsWith("http://127.0.0.1")) {
                    Toast.makeText(MainActivity.this,
                            "加载页面失败 (错误 " + errorCode + "): " + description, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, android.webkit.WebResourceRequest request,
                                            android.webkit.WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                if (request.isForMainFrame()) {
                    int code = errorResponse.getStatusCode();
                    Log.e("QuickSend", "HTTP error on main frame: " + code + " " + request.getUrl());
                    Toast.makeText(MainActivity.this,
                            "服务器返回错误: HTTP " + code, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                String title = view.getTitle();
                Log.i("QuickSend", "WebView page finished: " + url + " title=" + title);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            // === 剪贴板桥接：WebView JS 调 prompt() 时走这里 ===
            @Override
            public boolean onJsPrompt(WebView view, String url, String message,
                                       String defaultValue, android.webkit.JsPromptResult result) {
                if (message != null && message.startsWith("quicksend://clipboard")) {
                    try {
                        int q = message.indexOf('?');
                        if (q >= 0) {
                            String params = message.substring(q + 1);
                            String[] pairs = params.split("&");
                            for (String pair : pairs) {
                                int eq = pair.indexOf('=');
                                if (eq > 0) {
                                    String k = pair.substring(0, eq);
                                    String v = java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                                    if ("set".equals(k)) {
                                        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                        if (cm != null) {
                                            cm.setPrimaryClip(ClipData.newPlainText("QuickSend", v));
                                            result.confirm("ok");
                                            return true;
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) { }
                }
                return super.onJsPrompt(view, url, message, defaultValue, result);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(intent, "选择文件"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            String name = extractFileName(url, contentDisposition);
            if (name == null || name.isEmpty()) {
                try {
                    String path = Uri.parse(url).getPath();
                    int last = path.lastIndexOf('/');
                    name = last >= 0 ? path.substring(last + 1) : path;
                    name = java.net.URLDecoder.decode(name, "UTF-8");
                } catch (Exception ignored) { }
                if (name == null || name.isEmpty()) {
                    name = "quicksend-download-" + System.currentTimeMillis();
                }
            }
            if (name.toLowerCase().endsWith(".bin") && url != null) {
                try {
                    String path = Uri.parse(url).getPath();
                    int last = path.lastIndexOf('/');
                    String urlName = last >= 0 ? path.substring(last + 1) : path;
                    urlName = java.net.URLDecoder.decode(urlName, "UTF-8");
                    int q = urlName.indexOf('?');
                    if (q >= 0) urlName = urlName.substring(0, q);
                    if (urlName.contains(".") && !urlName.toLowerCase().endsWith(".bin")) {
                        name = urlName;
                    } else {
                        String noBin = name.substring(0, name.length() - 4);
                        String ext = extFromMime(mimetype);
                        name = ext != null ? noBin + "." + ext : noBin;
                    }
                } catch (Exception ignored) { }
            }
            if (Build.VERSION.SDK_INT < 29
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                pendingUrl = url;
                pendingUserAgent = userAgent;
                pendingContentDisposition = contentDisposition;
                pendingMime = mimetype;
                pendingName = name;
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE_STORAGE);
                return;
            }
            startDownload(url, userAgent, contentDisposition, mimetype, name);
        });
    }

    private void startDownload(String url, String userAgent, String contentDisposition,
                               String mimetype, String rawName) {
        try {
            String name = rawName.replaceAll("[\\\\/:*?\"<>|]", "_");
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) {
                request.addRequestHeader("Cookie", cookie);
            }
            if (userAgent != null) {
                request.addRequestHeader("User-Agent", userAgent);
            }
            if (mimetype != null && !mimetype.isEmpty()) {
                request.setMimeType(mimetype);
            }
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setTitle(name);
            request.setDescription("保存到 Download/" + DOWNLOAD_SUBDIR);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    DOWNLOAD_SUBDIR + "/" + name);
            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) {
                Toast.makeText(this, "下载服务不可用", Toast.LENGTH_LONG).show();
                return;
            }
            lastDownloadId = manager.enqueue(request);
            lastDownloadName = name;
            Toast.makeText(this, "开始下载：" + name + "（保存到 Download/" + DOWNLOAD_SUBDIR + "）",
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "下载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showOpenDownloadDialog(long id, String name) {
        if (isFinishing()) return;
        String label = name == null ? "文件" : name;
        new AlertDialog.Builder(this)
                .setTitle("下载完成")
                .setMessage(label + "\n已保存到 Download/" + DOWNLOAD_SUBDIR)
                .setPositiveButton("用其他应用打开", (dialog, which) -> openWithOtherApp(id))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void openWithOtherApp(long id) {
        DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        Uri uri = manager == null ? null : manager.getUriForDownloadedFile(id);
        if (uri == null) {
            Toast.makeText(this, "文件不可用，请到 Download/" + DOWNLOAD_SUBDIR + " 中查看",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String mime = manager.getMimeTypeForDownloadedFile(id);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mime == null || mime.isEmpty() ? "*/*" : mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(Intent.createChooser(intent, "用其他应用打开"));
        } catch (Exception e) {
            Toast.makeText(this, "没有可打开该文件的应用，文件已保存到 Download/" + DOWNLOAD_SUBDIR,
                    Toast.LENGTH_LONG).show();
        }
    }

    private static String extractFileName(String url, String contentDisposition) {
        if (contentDisposition != null && !contentDisposition.isEmpty()) {
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "(?i)filename\\*\\s*=\\s*UTF-8''([^;]+)").matcher(contentDisposition);
                if (m.find()) {
                    String b64 = m.group(1).trim();
                    b64 = java.net.URLDecoder.decode(b64, "UTF-8");
                    byte[] decoded = android.util.Base64.decode(b64, android.util.Base64.URL_SAFE);
                    if (decoded != null && decoded.length > 0) {
                        return new String(decoded, "UTF-8");
                    }
                }
            } catch (Exception ignored) { }
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "(?i)filename\\s*=\\s*\"?([^\";]+)\"?").matcher(contentDisposition);
                if (m.find()) {
                    String name = m.group(1).trim();
                    name = java.net.URLDecoder.decode(name, "UTF-8");
                    if (!name.isEmpty()) return name;
                }
            } catch (Exception ignored) { }
        }
        if (url != null) {
            try {
                String path = Uri.parse(url).getPath();
                int last = path.lastIndexOf('/');
                String name = last >= 0 ? path.substring(last + 1) : path;
                name = java.net.URLDecoder.decode(name, "UTF-8");
                int q = name.indexOf('?');
                if (q >= 0) name = name.substring(0, q);
                if (!name.isEmpty()) return name;
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static String extFromMime(String mime) {
        if (mime == null) return null;
        switch (mime.toLowerCase()) {
            case "application/msword": return "doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document": return "docx";
            case "application/vnd.ms-excel": return "xls";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": return "xlsx";
            case "application/vnd.ms-powerpoint": return "ppt";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation": return "pptx";
            case "application/pdf": return "pdf";
            case "application/zip": return "zip";
            case "application/x-rar-compressed": return "rar";
            case "image/jpeg": return "jpg";
            case "image/png": return "png";
            case "image/gif": return "gif";
            case "image/webp": return "webp";
            case "text/plain": return "txt";
            case "text/csv": return "csv";
            case "text/html": return "html";
            default: return null;
        }
    }

    // === 热点 API：quicksend://hotspot?action=start|stop|status ===
    private void handleHotspotRequest(String url) {
        try {
            Uri uri = Uri.parse(url);
            String action = uri.getQueryParameter("action");
            if (action == null) {
                webView.evaluateJavascript("__quicksend.onHotspotResult({error:'no_action'})", null);
                return;
            }
            String result = "";
            switch (action) {
                case "start":
                    if (hotspotHelper.needsNearbyWifiPermission()) {
                        requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, REQ_NEARBY_WIFI);
                        webView.evaluateJavascript("__quicksend.onHotspotResult({need_permission:'NEARBY_WIFI_DEVICES'})", null);
                        return;
                    }
                    if (hotspotHelper.needsLocationPermission()) {
                        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
                        webView.evaluateJavascript("__quicksend.onHotspotResult({need_permission:'ACCESS_FINE_LOCATION'})", null);
                        return;
                    }
                    result = hotspotHelper.startHotspot();
                    break;
                case "stop":
                    result = hotspotHelper.stopHotspot();
                    if (!result.startsWith("error:")) {
                        // 关闭后地址回到 Wi-Fi 地址，直接推给前端，避免被下面的 'ok' 误判成已开启
                        publishLanIp();
                        if (webView != null) {
                            webView.evaluateJavascript(
                                    "__quicksend.onHotspotState({state:'stopped', ip:'"
                                            + jsEscape(hotspotHelper.getLanIp())
                                            + "', ssid:'', password:'', active:false})", null);
                        }
                        return;
                    }
                    break;
                case "status":
                    // 页面刷新后恢复热点状态（SSID/密码/地址），并让前端拿到最新地址
                    sendHotspotStatus();
                    return;
                default:
                    result = "error:unknown_action:" + action;
            }
            // Handle fallback → show reason, then guide to system hotspot settings
            if ("fallback".equals(result) || result.startsWith("error:")) {
                String errMsg = "fallback".equals(result) ? "fallback:unknown" : "fallback:" + result.substring(6);
                Log.w("QuickSend", "Hotspot start fallback, reason=" + errMsg);
                if (webView != null) {
                    webView.evaluateJavascript(
                            "__quicksend.onHotspotState({state:'" + errMsg.replace("'", "\\'") + "'})", null);
                }
                showHotspotFallbackDialog(errMsg);
                return;
            }
            String lanIp = hotspotHelper.getHotspotIp();
            webView.evaluateJavascript(
                    "__quicksend.onHotspotResult({ip:'" + (lanIp != null ? lanIp : "") + "', status:'" + result.replace("'", "\\'") + "'})",
                    null);
        } catch (Exception e) {
            webView.evaluateJavascript("__quicksend.onHotspotResult({error:'" + e.getMessage().replace("'", "\\'") + "'})", null);
        }
    }

    /** 当前热点/本机地址写给 Python（filesDir/lan_ip.txt），/api/ip 会动态读取 */
    private void publishLanIp() {
        String ip = hotspotHelper == null ? null : hotspotHelper.getLanIp();
        // 必须和 Python 的 QUICKSEND_DATA_ROOT 一致：Service 传给 Python 的是 getExternalFilesDir(null)
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        try {
            if (!dir.exists()) dir.mkdirs();
            try (java.io.FileWriter w = new java.io.FileWriter(new File(dir, "lan_ip.txt"), false)) {
                w.write(ip == null ? "" : ip);
            }
            Log.i("QuickSend", "publishLanIp: " + ip + " -> " + dir);
        } catch (Exception e) {
            Log.w("QuickSend", "publishLanIp failed: " + e.getMessage());
        }
    }

    /** 把当前热点状态推给前端（页面刷新后也能恢复卡片状态） */
    private void sendHotspotStatus() {
        boolean active = hotspotHelper != null && hotspotHelper.isHotspotActive();
        String state = active ? "started"
                : (hotspotHelper != null && hotspotHelper.isStarting() ? "starting" : "idle");
        String ip = active ? hotspotHelper.getHotspotIp() : hotspotHelper.getLanIp();
        String ssid = hotspotHelper != null ? jsEscape(hotspotHelper.getHotspotSsid()) : "";
        String pwd = hotspotHelper != null ? jsEscape(hotspotHelper.getHotspotPassword()) : "";
        Log.i("QuickSend", "status: state=" + state + " ip=" + ip + " ssid=" + ssid);
        publishLanIp();
        if (webView != null) {
            webView.evaluateJavascript(
                    "__quicksend.onHotspotState({state:'" + state + "', ip:'" + jsEscape(ip)
                            + "', ssid:'" + ssid + "', password:'" + pwd + "', active:" + active + "})",
                    null);
        }
    }

    // === 请求权限：quicksend://request-permission?name=NEARBY_WIFI_DEVICES ===
    private void handlePermissionRequest(String url) {
        try {
            Uri uri = Uri.parse(url);
            String name = uri.getQueryParameter("name");
            if ("NEARBY_WIFI_DEVICES".equals(name)) {
                requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, REQ_NEARBY_WIFI);
            } else if ("ACCESS_FINE_LOCATION".equals(name)) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
            }
        } catch (Exception ignored) { }
    }

    // === HotspotHelper.HotspotStateListener 回调 ===
    @Override
    public void onHotspotStateChanged(final String state, final String lanIp) {
        runOnUiThread(() -> {
            Log.i("QuickSend", "onHotspotStateChanged: state=" + state + " ip=" + lanIp);
            if (state.startsWith("fallback")) {
                // LocalOnlyHotspot failed → explain the reason, then offer system settings
                Log.w("QuickSend", "Hotspot callback fallback: " + state);
                webView.evaluateJavascript(
                        "__quicksend.onHotspotState({state:'" + state.replace("'", "\\'") + "', ip:'" + (lanIp != null ? lanIp : "") + "'})",
                        null);
                showHotspotFallbackDialog(state);
                return;
            }
            if (webView != null) {
                String ssid = hotspotHelper != null ? jsEscape(hotspotHelper.getHotspotSsid()) : "";
                String pwd = hotspotHelper != null ? jsEscape(hotspotHelper.getHotspotPassword()) : "";
                // 热点启动/停止都会改变本机可用地址，同步给 Python 供 /api/ip 使用
                publishLanIp();
                webView.evaluateJavascript(
                        "__quicksend.onHotspotState({state:'" + jsEscape(state) + "', ip:'" + jsEscape(lanIp)
                                + "', ssid:'" + ssid + "', password:'" + pwd + "', active:" + state.startsWith("started") + "})",
                        null);
            }
        });
    }

    // === 前端调用「打开」文件：quicksend://open?path=<encoded>&mime=<encoded> ===
    // 关键修复：用 FileProvider 替代 Uri.fromFile()，否则 Android 7+ 崩溃
    private void handleNativeOpen(String url) {
        try {
            Uri uri = Uri.parse(url);
            String path = uri.getQueryParameter("path");
            String mime = uri.getQueryParameter("mime");
            if (path == null || path.isEmpty()) {
                Toast.makeText(this, "无效的打开请求", Toast.LENGTH_SHORT).show();
                return;
            }
            File file = new File(path);
            if (!file.exists()) {
                Toast.makeText(this, "文件不存在：" + path, Toast.LENGTH_SHORT).show();
                return;
            }

            // === 核心修复：用 FileProvider 生成安全 URI ===
            Uri fileUri;
            try {
                fileUri = FileProvider.getUriForFile(this, FILEPROVIDER_AUTH, file);
            } catch (IllegalArgumentException e) {
                // FileProvider 没配置这个路径，退而求其次用 Uri.fromFile (老设备兼容)
                fileUri = Uri.fromFile(file);
                Toast.makeText(this, "文件路径不在 FileProvider 白名单内，可能无法打开", Toast.LENGTH_SHORT).show();
            }

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(fileUri, mime == null || mime.isEmpty() ? "*/*" : mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            // 给所有可能的包授予权限
            List<ResolveInfo> resInfoList = getPackageManager().queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            for (android.content.pm.ResolveInfo resolveInfo : resInfoList) {
                String packageName = resolveInfo.activityInfo.packageName;
                grantUriPermission(packageName, fileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }

            startActivity(Intent.createChooser(intent, "用其他应用打开"));
        } catch (Exception e) {
            Toast.makeText(this, "打开失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String jsEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"").replace("\n", " ");
    }

    private AlertDialog hotspotFallbackDialog;

    /** 把 LocalOnlyHotspot 的失败原因翻译成用户能看懂的话，并引导手动开热点 */
    private void showHotspotFallbackDialog(String state) {
        String code = state == null ? "" : state;
        if (code.startsWith("fallback:")) code = code.substring("fallback:".length());
        String base;
        if (code.startsWith("no_channel")) {
            base = "系统没有可用的热点信道（常见原因是当前 Wi-Fi 占用了信道）。";
        } else if (code.startsWith("incompatible_mode")) {
            base = "系统当前模式不支持开启热点（例如正在投屏或使用无线显示）。";
        } else if (code.startsWith("tethering_disallowed")) {
            base = "系统或设备管理策略禁止开启热点。";
        } else if (code.startsWith("timeout")) {
            base = "系统在 12 秒内没有响应，该机型可能不允许应用内开启热点。";
        } else if (code.startsWith("security")) {
            base = "系统拒绝了热点请求（权限不足或机型限制）。";
        } else if (code.startsWith("illegal_state") || code.startsWith("not_supported")) {
            base = "系统当前状态不允许开启热点（可能 Wi-Fi 已关闭或该机型限制）。";
        } else if (code.startsWith("retry_")) {
            base = "重试后系统仍拒绝开启热点。";
        } else if (code.startsWith("permission_needed")) {
            base = "缺少开启热点所需的系统权限。";
        } else {
            base = "系统返回自动开热点失败。";
        }
        if (code.contains("wifi_on")) {
            base += "\n\n检测到 Wi-Fi 已连接，建议先关闭 Wi-Fi 再点一次「一键开热点」。";
        }
        base += "\n\n原因代码：" + (code.isEmpty() ? "unknown" : code)
                + "\n\n你也可以在系统设置中手动开启「个人热点」，再回到本应用。";
        if (isFinishing()) return;
        if (hotspotFallbackDialog != null && hotspotFallbackDialog.isShowing()) {
            hotspotFallbackDialog.dismiss();
        }
        hotspotFallbackDialog = new AlertDialog.Builder(this)
                .setTitle("无法自动开启热点")
                .setMessage(base)
                .setPositiveButton("去系统设置", (dialog, which) -> openHotspotSettings())
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 各机型热点设置页 Action 不一致，按顺序尝试，避免 No Activity found */
    private boolean openHotspotSettings() {
        String[] actions = new String[]{
                "android.settings.WIFI_AP_SETTINGS",
                "android.settings.TETHER_SETTINGS",
                Settings.ACTION_WIRELESS_SETTINGS,
                Settings.ACTION_SETTINGS
        };
        for (String action : actions) {
            try {
                Intent intent = new Intent(action);
                if (intent.resolveActivity(getPackageManager()) != null) {
                    startActivity(intent);
                    Log.i("QuickSend", "Opened settings: " + action);
                    return true;
                }
            } catch (Exception e) {
                Log.w("QuickSend", "Settings action failed: " + action + " — " + e.getMessage());
            }
        }
        Toast.makeText(this, "无法打开热点设置，请手动进入「设置 → 个人热点」", Toast.LENGTH_LONG).show();
        return false;
    }

    private void waitForServer() {
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + 90000L;
            boolean ready = false;
            while (System.currentTimeMillis() < deadline) {
                if (probe()) {
                    ready = true;
                    break;
                }
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    return;
                }
            }
            boolean finalReady = ready;
            runOnUiThread(() -> {
                if (finalReady) {
                    // === 诊断：Java HTTP 直接拉首页，看 Flask 返回什么 ===
                    new Thread(() -> {
                        try {
                            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                                    new java.net.URL("http://127.0.0.1:" + PORT + "/").openConnection();
                            c.setConnectTimeout(3000);
                            c.setReadTimeout(3000);
                            c.setRequestMethod("GET");
                            int code = c.getResponseCode();
                            StringBuilder sb = new StringBuilder();
                            try (java.io.BufferedReader br = new java.io.BufferedReader(
                                    new java.io.InputStreamReader(
                                            code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream(),
                                            "UTF-8"))) {
                                String line;
                                while ((line = br.readLine()) != null && sb.length() < 800) {
                                    sb.append(line).append('\n');
                                }
                            }
                            Log.i("QuickSend", "HOME PAGE DIAGNOSTIC: HTTP " + code
                                    + " len=" + sb.length()
                                    + " head=" + sb.toString().replace("\n", " ").substring(0, Math.min(400, sb.length())));
                        } catch (Exception e) {
                            Log.e("QuickSend", "HOME PAGE DIAGNOSTIC FAILED: " + e.getMessage());
                        }
                    }, "quicksend-home-diag").start();

                    splash.setVisibility(View.GONE);
                    publishLanIp();   // 服务起来后先同步一次本机地址给 Python
                    // Inject build number into JS globals so frontend can display version
                    String injectJs = "window.__QUICKSEND_BUILD__ = " + BuildConfig.BUILD_NUMBER + ";";
                    String loadUrl = "http://127.0.0.1:" + PORT + "/";
                    // Add headers for first load AND for subsequent requests via WebView
                    final java.util.Map<String, String> headers = new java.util.HashMap<>();
                    headers.put("X-QuickSend-Build", String.valueOf(BuildConfig.BUILD_NUMBER));
                    headers.put("X-Requested-With", "QuickSend");
                    webView.loadUrl(loadUrl, headers);
                    // Also inject via evaluateJavascript shortly after page loads
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (webView != null) {
                            webView.evaluateJavascript(injectJs, null);
                            webView.evaluateJavascript(
                                    "if(window.__quicksend) window.__quicksend.build = " + BuildConfig.BUILD_NUMBER + ";", null);
                        }
                    }, 1500);
                } else {
                    String errorLog = readPythonError();
                    String msg = "服务启动超时\n\n";
                    if (errorLog != null && !errorLog.isEmpty()) {
                        msg += "【Python 错误日志】\n" + errorLog;
                    } else {
                        msg += "（未找到 Python 错误日志）";
                    }
                    splash.setText(msg);
                }
            });
        }, "quicksend-probe").start();
    }

    private String readPythonError() {
        try {
            File errLog = new File(getFilesDir(), "python_error.log");
            if (!errLog.exists()) return null;
            FileReader fr = new FileReader(errLog);
            BufferedReader br = new BufferedReader(fr);
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            br.close();
            String full = sb.toString();
            return full.length() > 2000 ? full.substring(0, 2000) + "...(截断)" : full;
        } catch (Exception e) {
            return "读取错误日志失败: " + e.getMessage();
        }
    }

    private boolean probe() {
        // === 改成真正的 HTTP 探针：等 Flask 能真正响应请求才算 ready ===
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 800);
        } catch (IOException e) {
            return false;  // 端口都没监听 → 还没启动
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
        }
        // TCP 通了，但 Flask 可能还在初始化 → 发真正的 HTTP 请求
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL("http://127.0.0.1:" + PORT + "/api/ip").openConnection();
            conn.setConnectTimeout(800);
            conn.setReadTimeout(800);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            return code >= 200 && code < 500;  // 2xx 3xx 4xx 都算服务活着（404 也比没响应好）
        } catch (IOException e) {
            return false;  // Flask 还没 ready 处理 HTTP
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_WRITE_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && pendingUrl != null) {
                startDownload(pendingUrl, pendingUserAgent, pendingContentDisposition, pendingMime, pendingName);
            } else {
                Toast.makeText(this, "未授予存储权限，无法保存到公共 Download 目录",
                        Toast.LENGTH_LONG).show();
            }
            pendingUrl = null;
            pendingUserAgent = null;
            pendingContentDisposition = null;
            pendingMime = null;
            pendingName = null;
            return;
        }
        if (requestCode == REQ_NEARBY_WIFI || requestCode == REQ_LOCATION) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            String permName = (requestCode == REQ_NEARBY_WIFI) ? "NEARBY_WIFI_DEVICES" : "ACCESS_FINE_LOCATION";
            String action = "granted";
            if (!granted) action = "denied";
            if (webView != null) {
                webView.evaluateJavascript(
                        "__quicksend.onHotspotResult({perm:'" + permName + "', perm_action:'" + action + "'})", null);
            }
            // If granted, retry startHotspot
            if (granted && hotspotHelper != null) {
                String result = hotspotHelper.startHotspot();
                // startHotspot returns: "starting"/"ok" → async OK
                //   "fallback"/"error:xxx"/"permission_needed:xxx" → sync fail
                if ("fallback".equals(result) || result.startsWith("error:") || result.startsWith("permission_needed:")) {
                    String errMsg = "fallback".equals(result) ? "fallback:unknown"
                            : (result.startsWith("error:") ? "fallback:" + result.substring(6) : result);
                    Log.w("QuickSend", "Hotspot retry sync fail: " + errMsg);
                    if (webView != null) {
                        webView.evaluateJavascript(
                                "__quicksend.onHotspotState({state:'" + errMsg.replace("'", "\\'") + "'})", null);
                    }
                    showHotspotFallbackDialog(errMsg);
                } else {
                    // "starting" or "ok" → 正常路径，等 LocalOnlyHotspot 回调
                    String lanIp = hotspotHelper.getHotspotIp();
                    if (webView != null) {
                        webView.evaluateJavascript(
                                "__quicksend.onHotspotResult({ip:'" + (lanIp != null ? lanIp : "") + "', status:'" + result.replace("'", "\\'") + "'})",
                                null);
                    }
                }
            }
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            if (fileCallback != null) {
                fileCallback.onReceiveValue(results);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (webView == null) {
            performExit();
            return;
        }
        // 先问前端有没有打开的弹窗：有就关弹窗，没有才退出
        webView.evaluateJavascript(
                "(function(){try{return (window.__quicksend&&window.__quicksend.onBackPressed)"
                        + "?window.__quicksend.onBackPressed():'exit';}catch(e){return 'exit';}})()",
                value -> {
                    boolean handled = value != null && value.contains("handled");
                    if (handled) return;
                    if (webView != null && webView.canGoBack()) {
                        webView.goBack();
                    } else {
                        performExit();
                    }
                });
    }

    private void performExit() {
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到前台时补一次地址刷新（有些机型不回调网络变化）
        scheduleNetworkRefresh();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(downloadReceiver);
        } catch (IllegalArgumentException ignored) { }
        try {
            unregisterReceiver(shareDoneReceiver);
        } catch (IllegalArgumentException ignored) { }
        unregisterNetworkMonitor();
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
