package com.pdf.reader;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.print.PrintManager;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.pdf.reader.net.WifiConnector;
import com.pdf.reader.print.PdfPrintAdapter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int ACTION_NONE = 0;
    private static final int ACTION_CONNECT = 1;
    private static final int ACTION_SCAN = 2;

    private EditText etSsid;
    private EditText etPassword;
    private Button btnConnect;
    private Button btnDisconnect;
    private Button btnChoosePdf;
    private Button btnPrintPdf;
    private Button btnPrintSettings;
    private Button btnScan;
    private TextView tvStatus;
    private TextView tvSelected;
    private ListView lvNetworks;

    private WifiConnector wifiConnector;
    private WifiManager wifiManager;

    private BroadcastReceiver wifiScanReceiver;
    private boolean scanReceiverRegistered = false;

    private ArrayAdapter<String> networksAdapter;
    private final List<String> networks = new ArrayList<>();

    private Uri selectedPdfUri;
    private SharedPreferences prefs;

    private int pendingAction = ACTION_NONE;

    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean granted = areAllRequiredPermissionsGranted(result);
                if (!granted) {
                    tvStatus.setText("状态: 未授予必要权限，操作已取消");
                    pendingAction = ACTION_NONE;
                    return;
                }
                int action = pendingAction;
                pendingAction = ACTION_NONE;
                if (action == ACTION_CONNECT) {
                    startConnect();
                } else if (action == ACTION_SCAN) {
                    startScan();
                }
            });

    private final ActivityResultLauncher<Intent> pickPdfLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), activityResult -> {
                if (activityResult.getResultCode() == RESULT_OK && activityResult.getData() != null) {
                    Uri uri = activityResult.getData().getData();
                    if (uri != null) {
                        final int takeFlags = activityResult.getData().getFlags()
                                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                        try {
                            getContentResolver().takePersistableUriPermission(uri, takeFlags);
                        } catch (Exception ignored) { }
                        selectedPdfUri = uri;
                        tvSelected.setText("已选择文件: " + uri.toString());
                        btnPrintPdf.setEnabled(true);
                    }
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("printer_prefs", MODE_PRIVATE);

        etSsid = findViewById(R.id.etSsid);
        etPassword = findViewById(R.id.etPassword);
        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        btnChoosePdf = findViewById(R.id.btnChoosePdf);
        btnPrintPdf = findViewById(R.id.btnPrintPdf);
        btnPrintSettings = findViewById(R.id.btnPrintSettings);
        btnScan = findViewById(R.id.btnScan);
        tvStatus = findViewById(R.id.tvStatus);
        tvSelected = findViewById(R.id.tvSelected);
        lvNetworks = findViewById(R.id.lvNetworks);

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        networksAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, networks);
        lvNetworks.setAdapter(networksAdapter);
        lvNetworks.setOnItemClickListener((AdapterView<?> parent, View view, int position, long id) -> {
            String ssid = networks.get(position);
            etSsid.setText(ssid);
            tvStatus.setText("状态: 已选择网络 " + ssid);
        });

        wifiConnector = new WifiConnector(this, new WifiConnector.Callback() {
            @Override
            public void onConnecting() { runOnUiThread(() -> tvStatus.setText("状态: 正在连接…")); }
            @Override
            public void onConnected() { runOnUiThread(() -> tvStatus.setText("状态: 已连接到打印机热点")); }
            @Override
            public void onDisconnected() { runOnUiThread(() -> tvStatus.setText("状态: 已断开连接")); }
            @Override
            public void onFailed(String reason) { runOnUiThread(() -> tvStatus.setText("状态: 连接失败 - " + reason)); }
        });

        // 恢复上次填写
        etSsid.setText(prefs.getString("ssid", ""));
        etPassword.setText(prefs.getString("password", ""));

        btnConnect.setOnClickListener(v -> {
            pendingAction = ACTION_CONNECT;
            requestPermissionsForCurrentApi();
        });

        btnDisconnect.setOnClickListener(v -> wifiConnector.disconnect());

        btnScan.setOnClickListener(v -> {
            pendingAction = ACTION_SCAN;
            requestPermissionsForCurrentApi();
        });

        btnChoosePdf.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/pdf");
            pickPdfLauncher.launch(intent);
        });

        btnPrintPdf.setOnClickListener(v -> {
            if (selectedPdfUri == null) {
                tvStatus.setText("状态: 请先选择 PDF 文件");
                return;
            }
            printSelectedPdf(selectedPdfUri);
        });

        btnPrintSettings.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_PRINT_SETTINGS));
            } catch (Exception e) {
                tvStatus.setText("状态: 无法打开打印设置，请在系统设置里搜索“打印”启用 Mopria/Epson 服务");
            }
        });

        btnPrintPdf.setEnabled(false);
        tvStatus.setText("状态: 就绪");
    }

    private void requestPermissionsForCurrentApi() {
        // 为了兼容部分鸿蒙/定制系统，在 33+ 同时请求 NEARBY_WIFI_DEVICES 与 ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissionLauncher.launch(new String[]{
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.ACCESS_FINE_LOCATION
            });
        } else {
            requestPermissionLauncher.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION});
        }
    }

    private boolean areAllRequiredPermissionsGranted(java.util.Map<String, Boolean> result) {
        if (Build.VERSION.SDK_INT >= 33) {
            boolean near = Boolean.TRUE.equals(result.get(Manifest.permission.NEARBY_WIFI_DEVICES));
            boolean fine = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION));
            // 有些 13+ 设备扫描仍需定位权限，连接需要 NEARBY，因此同时校验
            return near && fine;
        } else {
            return Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION));
        }
    }

    private void startConnect() {
        String ssid = etSsid.getText().toString().trim();
        String password = etPassword.getText().toString().trim();
        if (ssid.isEmpty()) {
            tvStatus.setText("状态: 请输入或选择打印机热点 SSID");
            return;
        }
        // 保存最近填写
        prefs.edit().putString("ssid", ssid).putString("password", password).apply();
        wifiConnector.connect(ssid, password);
    }

    private void startScan() {
        if (wifiManager == null) {
            tvStatus.setText("状态: 设备不支持 Wi‑Fi");
            return;
        }
        tvStatus.setText("状态: 正在扫描附近网络…");
        registerScanReceiverIfNeeded();

        boolean started = false;
        try {
            started = wifiManager.startScan();
        } catch (SecurityException se) {
            tvStatus.setText("状态: 无权扫描，请在系统设置授予定位/Wi‑Fi 权限");
            return;
        }

        if (!started) {
            // 某些系统限制主动扫描，直接读取最近一次结果
            updateScanResults();
        }
    }

    private void registerScanReceiverIfNeeded() {
        if (scanReceiverRegistered) return;
        if (wifiScanReceiver == null) {
            wifiScanReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
                        updateScanResults();
                    }
                }
            };
        }
        IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        registerReceiver(wifiScanReceiver, filter);
        scanReceiverRegistered = true;
    }

    private void unregisterScanReceiverIfNeeded() {
        if (scanReceiverRegistered && wifiScanReceiver != null) {
            try {
                unregisterReceiver(wifiScanReceiver);
            } catch (Exception ignored) { }
            scanReceiverRegistered = false;
        }
    }

    private void updateScanResults() {
        try {
            List<ScanResult> results = wifiManager.getScanResults();
            if (results == null) results = new ArrayList<>();

            // 先挑出 Epson/L8058/Direct 的网络；若为空则显示全部
            List<String> preferred = new ArrayList<>();
            List<String> others = new ArrayList<>();
            Set<String> dedup = new LinkedHashSet<>();

            for (ScanResult sr : results) {
                if (sr == null || sr.SSID == null || sr.SSID.isEmpty()) continue;
                String ssid = sr.SSID;
                if (dedup.contains(ssid)) continue;
                dedup.add(ssid);
                String lower = ssid.toLowerCase(Locale.ROOT);
                if (lower.contains("epson") || lower.contains("l8058") || lower.startsWith("direct-")) {
                    preferred.add(ssid);
                } else {
                    others.add(ssid);
                }
            }

            List<String> finalList = preferred.isEmpty() ? new ArrayList<>(dedup) : preferred;
            networks.clear();
            networks.addAll(finalList);
            networksAdapter.notifyDataSetChanged();

            tvStatus.setText("状态: 扫描完成，发现 " + finalList.size() + " 个网络"
                    + (preferred.isEmpty() ? "（未检测到明显的 Epson/L8058，已显示全部）" : ""));
        } catch (SecurityException se) {
            tvStatus.setText("状态: 无权读取扫描结果，请授予权限");
        }
    }

    private void printSelectedPdf(Uri uri) {
        try {
            ContentResolver resolver = getContentResolver();
            String jobName = "打印PDF";
            PrintManager printManager = (PrintManager) getSystemService(PRINT_SERVICE);
            PdfPrintAdapter adapter = new PdfPrintAdapter(this, resolver, uri, jobName);
            printManager.print(jobName, adapter, null);
        } catch (Exception e) {
            tvStatus.setText("状态: 启动打印失败 - " + e.getMessage());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 退出时记住上次填写
        prefs.edit()
                .putString("ssid", etSsid.getText().toString().trim())
                .putString("password", etPassword.getText().toString().trim())
                .apply();
        unregisterScanReceiverIfNeeded();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterScanReceiverIfNeeded();
        wifiConnector.disconnect();
    }
}
