package com.pdf.reader.net;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 使用 WifiNetworkSuggestion 提交系统级连接建议（Android 10+）
 * 说明：
 * - 与 WifiNetworkSpecifier 不同，Suggestion 连接是系统层的，打印服务等其他应用也能访问该网络；
 * - 首次添加建议后，系统可能在通知栏提示“有可用的网络建议”，用户需点“连接”或在 Wi‑Fi 面板选择；
 * - 通过广播与网络回调尽量感知连接结果，但最终连不连由系统决策。
 */
public class WifiConnector {

    public interface Callback {
        void onConnecting();
        void onConnected();
        void onDisconnected();
        void onFailed(String reason);
    }

    private final Context appContext;
    private final WifiManager wifiManager;
    private final ConnectivityManager cm;
    private final Callback callback;

    private String targetSsid;
    private BroadcastReceiver suggestionReceiver;
    private boolean receiverRegistered = false;
    private ConnectivityManager.NetworkCallback networkCallback;

    public WifiConnector(@NonNull Context context, @NonNull Callback callback) {
        this.appContext = context.getApplicationContext();
        this.wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
        this.cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.callback = callback;
    }

    public void connect(@NonNull String ssid, @NonNull String password) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (callback != null) callback.onFailed("当前系统版本过低，需 Android 10+");
            return;
        }
        if (wifiManager == null) {
            if (callback != null) callback.onFailed("设备不支持 Wi‑Fi");
            return;
        }

        disconnect(); // 清理旧状态

        targetSsid = ssid;
        if (callback != null) callback.onConnecting();

        // 1) 提交建议
        WifiNetworkSuggestion.Builder builder = new WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setIsAppInteractionRequired(true); // 更容易触发系统交互面板/通知

        if (!password.isEmpty()) {
            builder.setWpa2Passphrase(password);
        }
        WifiNetworkSuggestion suggestion = builder.build();

        List<WifiNetworkSuggestion> list = new ArrayList<>();
        list.add(suggestion);

        // 先移除旧建议，避免堆积
        try { wifiManager.removeNetworkSuggestions(list); } catch (Exception ignored) {}

        int status = wifiManager.addNetworkSuggestions(list);
        if (status != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            if (callback != null) callback.onFailed("提交连接建议失败，状态码 " + status);
            return;
        }

        // 2) 监听建议连接成功广播（Android 10+）
        registerSuggestionReceiver();

        // 3) 监听默认网络变化，判断是否已连到目标 SSID
        registerNetworkCallback();

        // 4) 尝试立即判断当前连接（如果本来就连着）
        if (isCurrentlyConnectedToTarget()) {
            if (callback != null) callback.onConnected();
        }
        // 实际连接可能需要用户在通知栏点确认，或在 Wi‑Fi 面板选择；请在 UI 里引导用户
    }

    public void disconnect() {
        // 清理广播
        if (receiverRegistered && suggestionReceiver != null) {
            try { appContext.unregisterReceiver(suggestionReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        suggestionReceiver = null;

        // 清理网络回调
        if (networkCallback != null) {
            try { cm.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
            networkCallback = null;
        }

        // 移除建议
        if (wifiManager != null && targetSsid != null) {
            WifiNetworkSuggestion dummy = new WifiNetworkSuggestion.Builder().setSsid(targetSsid).build();
            List<WifiNetworkSuggestion> list = new ArrayList<>();
            list.add(dummy);
            try { wifiManager.removeNetworkSuggestions(list); } catch (Exception ignored) {}
        }

        targetSsid = null;
        if (callback != null) callback.onDisconnected();
    }

    private void registerSuggestionReceiver() {
        if (receiverRegistered) return;
        suggestionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // 当通过建议成功连接某网络时会触发此广播
                if (WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION.equals(intent.getAction())) {
                    if (isCurrentlyConnectedToTarget()) {
                        if (callback != null) callback.onConnected();
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION);
        appContext.registerReceiver(suggestionReceiver, filter);
        receiverRegistered = true;
    }

    private void registerNetworkCallback() {
        if (networkCallback != null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                if (isCurrentlyConnectedToTarget()) {
                    if (callback != null) callback.onConnected();
                }
            }

            @Override
            public void onLost(@NonNull Network network) {
                // 丢失默认网络或者 Wi‑Fi 切走
                if (callback != null) callback.onDisconnected();
            }

            @Override
            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities caps) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    if (isCurrentlyConnectedToTarget()) {
                        if (callback != null) callback.onConnected();
                    }
                }
            }
        };
        try {
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception ignored) { }
    }

    private boolean isCurrentlyConnectedToTarget() {
        if (wifiManager == null || targetSsid == null) return false;
        try {
            WifiInfo info = wifiManager.getConnectionInfo();
            if (info == null) return false;
            String current = info.getSSID();
            if (current == null) return false;
            // 系统返回的 SSID 可能带引号
            current = current.replace("\"", "");
            return current.equals(targetSsid);
        } catch (SecurityException se) {
            // 缺少定位权限会抛异常；外层已做权限校验，这里兜底返回 false
            return false;
        }
    }
}
