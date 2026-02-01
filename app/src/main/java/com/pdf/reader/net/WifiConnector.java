package com.pdf.reader.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import androidx.annotation.NonNull;

/**
 * 使用 WifiNetworkSpecifier 连接指定 SSID（Android 10+）
 */
public class WifiConnector {

    public interface Callback {
        void onConnecting();
        void onConnected();
        void onDisconnected();
        void onFailed(String reason);
    }

    private final ConnectivityManager cm;
    private final Callback callback;

    private ConnectivityManager.NetworkCallback networkCallback;
    private Network boundNetwork;

    public WifiConnector(@NonNull Context context, @NonNull Callback callback) {
        this.cm = (ConnectivityManager) context.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        this.callback = callback;
    }

    public void connect(@NonNull String ssid, @NonNull String password) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (callback != null) callback.onFailed("当前系统版本过低，需 Android 10+");
            return;
        }

        disconnect(); // 清理旧回调
        if (callback != null) callback.onConnecting();

        WifiNetworkSpecifier.Builder specBuilder = new WifiNetworkSpecifier.Builder()
                .setSsid(ssid);
        if (!password.isEmpty()) {
            specBuilder.setWpa2Passphrase(password);
        }

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specBuilder.build())
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
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

        cm.requestNetwork(request, networkCallback);
    }

    public void disconnect() {
        try {
            if (networkCallback != null) {
                cm.unregisterNetworkCallback(networkCallback);
            }
        } catch (Exception ignored) { }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && boundNetwork != null) {
            cm.bindProcessToNetwork(null);
            boundNetwork = null;
        }
        networkCallback = null;
        if (callback != null) callback.onDisconnected();
    }
}
