package com.quicksend.android;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 局域网 UDP 发现：向 5001 端口广播 QUICKSEND_DISCOVER，
 * 收集其它 QuickSend 服务器（Python 后端）的应答，返回 "ip:port" 列表。
 */
public class DiscoveryClient {

    private static final String TAG = "QuickSend";
    private static final int DISCOVERY_PORT = 5001;
    private static final byte[] PAYLOAD = "QUICKSEND_DISCOVER".getBytes();

    public static List<String> discover(int timeoutMs) {
        List<String> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(250);

            List<InetAddress> targets = new ArrayList<>();
            try {
                targets.add(InetAddress.getByName("255.255.255.255"));
            } catch (Exception ignored) { }
            try {
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        InetAddress broadcast = ia.getBroadcast();
                        if (broadcast != null && !targets.contains(broadcast)) {
                            targets.add(broadcast);
                        }
                    }
                }
            } catch (Exception ignored) { }

            for (InetAddress target : targets) {
                try {
                    socket.send(new DatagramPacket(PAYLOAD, PAYLOAD.length, target, DISCOVERY_PORT));
                } catch (Exception ignored) { }
            }

            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[512];
            while (System.currentTimeMillis() < deadline) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    String text = new String(packet.getData(), 0, packet.getLength(), "UTF-8").trim();
                    String address = parseReply(text);
                    if (address != null && seen.add(address)) {
                        results.add(address);
                    }
                } catch (java.net.SocketTimeoutException e) {
                    // 继续等待本轮剩余时间
                } catch (Exception e) {
                    break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "UDP discovery failed: " + e.getMessage());
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) { }
            }
        }
        return results;
    }

    /** 判断某个 IP 是不是本机网卡地址（用于过滤自己发出的广播应答） */
    public static boolean isLocalAddress(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (ip.equals(addr.getHostAddress())) return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static String parseReply(String text) {        try {
            org.json.JSONObject obj = new org.json.JSONObject(text);
            if (!"quicksend".equals(obj.optString("app"))) return null;
            String ip = obj.optString("ip", "");
            if (ip.isEmpty()) return null;
            int port = obj.optInt("port", 5000);
            return ip + ":" + port;
        } catch (Exception e) {
            return null;
        }
    }
}
