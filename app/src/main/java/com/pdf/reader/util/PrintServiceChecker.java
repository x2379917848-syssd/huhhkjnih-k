package com.pdf.reader.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 列出系统中可用的打印服务（实现了 android.printservice.PrintService 的 Service）。
 */
public final class PrintServiceChecker {
    private PrintServiceChecker() {}

    public static List<String> listInstalledPrintServices(Context context) {
        List<String> names = new ArrayList<>();
        try {
            PackageManager pm = context.getPackageManager();
            Intent intent = new Intent("android.printservice.PrintService");
            List<ResolveInfo> list = pm.queryIntentServices(intent, PackageManager.MATCH_ALL);
            if (list != null) {
                for (ResolveInfo ri : list) {
                    CharSequence label = ri.loadLabel(pm);
                    if (label != null) names.add(label.toString());
                }
            }
        } catch (Exception ignored) { }
        return names;
    }
}
