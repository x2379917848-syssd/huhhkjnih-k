package com.pdf.reader.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

/**
 * 使用 WifiNetworkSpecifier 连接到指定 SSID 的热点（Android 10+）
 * 连接/断开状态通过回调告知 UI。
 */
public class WifiConnector {

    public interface Callback {
        void onConnecting();
        void onConnected();
        void onDisconnected();
        void onFailed(String reason);
    }

    private final Context appContext;
    private final ConnectivityManager cm;
    private final Callback callback;

    private ConnectivityManager.NetworkCallback networkCallback;
    private Network boundNetwork;

    public WifiConnector(@NonNull Context context, @NonNull Callback callback) {
        this.appContext = context.getApplicationContext();
        this.cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.callback = callback;
    }

    public void connect(@NonNull String ssid, @NonNull String password) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (callback != null) {
                callback.onFailed("当前系统版本过低，需 Android 10+");
            }
            return;
        }

        disconnect(); // 清理旧回调

        if (callback != null) callback.onConnecting();

        WifiNetworkSpecifier.Builder specBuilder = new WifiNetworkSpecifier.Builder()
                .setSsid(ssid);

        if (!password.isEmpty()) {
            // Epson L8058 一般为 WPA2
            specBuilder.setWpa2Passphrase(password);
        }

        WifiNetworkSpecifier specifier = specBuilder.build();

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                // 打印机热点通常无互联网，移除对互联网能力的要求，避免系统偏好其他网络
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                // 绑定进程到该网络，确保后续通信走打印机热点（可按需移除）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    cm.bindProcessToNetwork(network);
                    boundNetwork = network;
                }
                if (callback != null) callback.onConnected();
            }

            @Override
            public void onUnavailable() {
                if (callback != null) callback.onFailed("网络不可用或用户未同意连接");
            }

            @Override
            public void onLost(@NonNull Network network) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    cm.bindProcessToNetwork(null);
                    boundNetwork = null;
                }
                if (callback != null) callback.onDisconnected();
            }
        };

        // 触发系统连接面板（用户需同意）
        cm.requestNetwork(request, networkCallback);
    }

    public void disconnect() {
        try {
            if (networkCallback != null) {
                cm.unregisterNetworkCallback(networkCallback);
            }
        } catch (Exception ignored) { }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (boundNetwork != null) {
                cm.bindProcessToNetwork(null);
                boundNetwork = null;
            }
        }
        networkCallback = null;
        if (callback != null) callback.onDisconnected();
    }
}
