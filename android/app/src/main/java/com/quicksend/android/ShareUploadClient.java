package com.quicksend.android;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把系统分享/打开进来的 content:// 文件，按 4MB 分片上传到 QuickSend 服务器。
 * 默认传本机（127.0.0.1），客户端模式可指定对方服务器地址。
 * 只依赖 Android 内置的 org.json，不引入额外库。
 */
class ShareUploadClient {

    private static final String TAG = "QuickSend-Share";
    private static final int CHUNK = 4 * 1024 * 1024;
    private static final int MAX_RETRY = 3;
    private static final String LOCAL_BASE = "http://127.0.0.1:" + QuickSendService.PORT;

    private final String base;
    private final String key;

    ShareUploadClient() {
        this(null, null);
    }

    ShareUploadClient(String baseUrl) {
        this(baseUrl, null);
    }

    ShareUploadClient(String baseUrl, String accessKey) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        String k = accessKey == null ? "" : accessKey.trim();
        // 扫描到的地址可能带 ?k=访问码：拆出来当请求头，地址本身不能带查询参数
        if (b.contains("?")) {
            try {
                Uri u = Uri.parse(b);
                String qk = u.getQueryParameter("k");
                if (k.isEmpty() && qk != null) k = qk.trim();
            } catch (Exception ignored) {
            }
            int q = b.indexOf('?');
            b = b.substring(0, q);
        }
        if (b.contains("#")) b = b.substring(0, b.indexOf('#'));
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        this.base = b.isEmpty() ? LOCAL_BASE : b;
        this.key = k;
    }

    /** 服务端开了访问码时，上传请求必须带上，否则 401 */
    private void applyAccessKey(HttpURLConnection conn) {
        if (key != null && !key.isEmpty()) {
            conn.setRequestProperty("X-QuickSend-Key", key);
        }
    }

    interface Listener {
        void onFileStart(String name, long size, int index, int count);

        void onFileProgress(String name, long uploaded, long total);

        void onFileUploaded(String name);

        void onFileFailed(String name, String reason);
    }

    static class Result {
        int ok = 0;
        int failed = 0;
        final List<String> errors = new ArrayList<>();
    }

    Result uploadAll(Context ctx, List<Uri> uris, Listener listener) {
        Result result = new Result();
        int count = uris.size();
        for (int i = 0; i < count; i++) {
            Uri uri = uris.get(i);
            String name = displayName(ctx, uri);
            long size = displaySize(ctx, uri);
            try {
                if (listener != null) listener.onFileStart(name, size, i, count);
                uploadOne(ctx, uri, name, size, listener);
                result.ok++;
                if (listener != null) listener.onFileUploaded(name);
            } catch (Exception e) {
                Log.e(TAG, "上传失败: " + name, e);
                result.failed++;
                result.errors.add(name + ": " + e.getMessage());
                if (listener != null) listener.onFileFailed(name, String.valueOf(e.getMessage()));
            }
        }
        return result;
    }

    private void uploadOne(Context ctx, Uri uri, String name, long size, Listener listener) throws Exception {
        String uploadId = makeUploadId(name, size);

        JSONObject initBody = new JSONObject();
        initBody.put("upload_id", uploadId);
        initBody.put("filename", name);
        initBody.put("size", Math.max(size, 0));
        JSONObject init = postJson("/api/upload/init", initBody, 200);

        Set<Integer> received = new HashSet<>();
        JSONArray arr = init.optJSONArray("received");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) received.add(arr.optInt(i));
        }

        long uploaded = 0;
        int index = 0;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("无法读取分享的文件");
            byte[] buf = new byte[CHUNK];
            while (true) {
                int len = readFully(in, buf);
                if (len <= 0) break;
                if (!received.contains(index)) {
                    postChunk(uploadId, index, buf, len);
                }
                uploaded += len;
                index++;
                if (listener != null) listener.onFileProgress(name, uploaded, size > 0 ? size : uploaded);
            }
        }
        if (index == 0) throw new IOException("文件为空");

        JSONObject done = new JSONObject();
        done.put("upload_id", uploadId);
        done.put("filename", name);
        // 服务端以实际合并大小为准，这里传 0 跳过大小校验，避免个别 provider 上报大小不准
        done.put("size", 0);
        done.put("uploader", "分享导入");
        done.put("password", "");
        done.put("group_id", "root");
        postJson("/api/upload/complete", done, 201);
    }

    private void postChunk(String uploadId, int index, byte[] data, int len) throws Exception {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                doPostChunk(uploadId, index, data, len);
                return;
            } catch (IOException e) {
                last = e;
                Log.w(TAG, "分片 " + index + " 第 " + attempt + " 次失败: " + e.getMessage());
                try {
                    Thread.sleep(800L * attempt);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (last != null) throw last;
        throw new IOException("分片上传失败");
    }

    private void doPostChunk(String uploadId, int index, byte[] data, int len) throws IOException {
        String boundary = "----QuickSendBoundary" + System.nanoTime();
        byte[] head1 = ("--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"upload_id\"\r\n\r\n" + uploadId + "\r\n")
                .getBytes("UTF-8");
        byte[] head2 = ("--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"index\"\r\n\r\n" + index + "\r\n")
                .getBytes("UTF-8");
        byte[] head3 = ("--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"chunk\"; filename=\"chunk\""
                + "\r\nContent-Type: application/octet-stream\r\n\r\n")
                .getBytes("UTF-8");
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes("UTF-8");
        long total = (long) head1.length + head2.length + head3.length + len + tail.length;

        HttpURLConnection conn = (HttpURLConnection) new URL(base + "/api/upload/chunk").openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(120000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setFixedLengthStreamingMode(total);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        applyAccessKey(conn);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(head1);
            out.write(head2);
            out.write(head3);
            out.write(data, 0, len);
            out.write(tail);
        }
        int code = conn.getResponseCode();
        String text = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        if (code != 200) throw new IOException("分片 " + index + " HTTP " + code + " " + trim(text));
    }

    private JSONObject postJson(String path, JSONObject body, int expect) throws Exception {
        byte[] payload = body.toString().getBytes("UTF-8");
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(120000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setFixedLengthStreamingMode(payload.length);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        applyAccessKey(conn);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(payload);
        }
        int code = conn.getResponseCode();
        String text = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        if (code != expect) throw new IOException(path + " HTTP " + code + " " + trim(text));
        if (text.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try (InputStream stream = in) {
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            while ((n = stream.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
                if (sb.length() > 4000) break;
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private static String trim(String text) {
        if (text == null) return "";
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private static String makeUploadId(String name, long size) {
        long h = 1125899906842597L;
        for (int i = 0; i < name.length(); i++) {
            h = 31 * h + name.charAt(i);
        }
        h &= 0x7fffffffffffffffL;
        return String.format(Locale.US, "share%016x%08x", h, size < 0 ? 0 : (size & 0xffffffffL));
    }

    static String displayName(Context ctx, Uri uri) {
        if (uri == null) return "shared_file";
        if ("content".equals(uri.getScheme())) {
            Cursor cursor = null;
            try {
                cursor = ctx.getContentResolver().query(
                        uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    String name = cursor.getString(0);
                    if (name != null && !name.trim().isEmpty()) return name.trim();
                }
            } catch (Exception ignored) {
            } finally {
                if (cursor != null) cursor.close();
            }
        }
        String segment = uri.getLastPathSegment();
        if (segment != null && !segment.trim().isEmpty()) {
            int slash = segment.lastIndexOf('/');
            return slash >= 0 ? segment.substring(slash + 1) : segment;
        }
        return "shared_file";
    }

    static long displaySize(Context ctx, Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())) {
            return -1;
        }
        Cursor cursor = null;
        try {
            cursor = ctx.getContentResolver().query(
                    uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return -1;
    }
}
