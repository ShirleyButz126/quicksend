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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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

public class MainActivity extends Activity {

    private static final int PORT = 5000;
    private static final int REQ_FILE = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;
    private static final int REQ_WRITE_STORAGE = 1003;
    private static final String DOWNLOAD_SUBDIR = "QuickSend";
    private static final String FILEPROVIDER_AUTH = "com.quicksend.android.fileprovider";

    private WebView webView;
    private TextView splash;
    private ValueCallback<Uri[]> fileCallback;

    private long lastDownloadId = -1;
    private String lastDownloadName;
    private String pendingUrl, pendingUserAgent, pendingContentDisposition, pendingMime, pendingName;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id != lastDownloadId) return;
            showOpenDownloadDialog(id, lastDownloadName);
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

        setupWebView();

        IntentFilter downloadFilter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, downloadFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, downloadFilter);
        }

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }

        Intent serviceIntent = new Intent(this, QuickSendService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        waitForServer();
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

        // 剪贴板相关：WebView 允许 execCommand('copy') 和 navigator.clipboard
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+ 需要显式声明 clipboard 写入
            webView.setWebViewClient(new WebViewClient() {
                // 我们在下方单独设置 WebViewClient
            });
        }

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && url.startsWith("quicksend://open")) {
                    handleNativeOpen(url);
                    return true;
                }
                return false;  // 返回 false 让 WebView 自己加载 http/https
            }

            // === 剪贴板桥接：WebView JS 调 prompt() 时走这里 ===
            @Override
            public boolean onJsPrompt(WebView view, String url, String message,
                                       String defaultValue, android.webkit.JsPromptResult result) {
                // 自定义协议：quicksend://clipboard?set=xxx
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
        });

        webView.setWebChromeClient(new WebChromeClient() {
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
                    splash.setVisibility(View.GONE);
                    webView.loadUrl("http://127.0.0.1:" + PORT + "/");
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
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 800);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) { }
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
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(downloadReceiver);
        } catch (IllegalArgumentException ignored) { }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
