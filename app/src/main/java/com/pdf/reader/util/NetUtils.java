package com.pdf.reader.util;

import android.content.Context;
import android.location.LocationManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;

public final class NetUtils {
    private NetUtils() {}

    public static boolean isLocationEnabled(Context context) {
        try {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return lm.isLocationEnabled();
            } else {
                int mode = Settings.Secure.getInt(context.getContentResolver(),
                        Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF);
                return mode != Settings.Secure.LOCATION_MODE_OFF;
            }
        } catch (Exception e) {
            return false;
        }
    }

    // 读取当前 Wi‑Fi SSID（需 ACCESS_FINE_LOCATION + 定位开关开启；返回去引号后的字符串）
    public static String getCurrentSsid(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return null;
            String ssid = info.getSSID();
            if (ssid == null || "<unknown ssid>".equalsIgnoreCase(ssid)) return null;
            if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            return ssid;
        } catch (SecurityException se) {
            return null;
        }
    }
}
