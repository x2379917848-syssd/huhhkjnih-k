package com.pdf.reader;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.print.PrintManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.pdf.reader.print.PdfPrintAdapter;
import com.pdf.reader.print.SelectivePdfPrintAdapter;
import com.pdf.reader.util.NetUtils;
import com.pdf.reader.util.PrintServiceChecker;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private Button btnChoosePdf;
    private Button btnPrintPdf;
    private Button btnPrintSettings;
    private Button btnWifiSettings;
    private Button btnSelfCheck;
    private TextView tvStatus;
    private TextView tvSelected;
    private RadioGroup rgPageMode;
    private RadioButton rbAll, rbOdd, rbEven;

    private Uri selectedPdfUri;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (areLocationPermissionsGranted(result)) {
                    doSelfCheck();
                } else {
                    appendStatus("未授予定位权限，无法检测当前 Wi‑Fi 连接。");
                }
            });

    private final ActivityResultLauncher<Intent> pickPdfLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), activityResult -> {
                if (activityResult.getResultCode() == RESULT_OK && activityResult.getData() != null) {
                    Uri uri = activityResult.getData().getData();
                    if (uri != null) {
                        final int takeFlags = activityResult.getData().getFlags()
                                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                        try { getContentResolver().takePersistableUriPermission(uri, takeFlags); } catch (Exception ignored) {}
                        selectedPdfUri = uri;
                        tvSelected.setText("已选择文件: " + uri.toString());
                        btnPrintPdf.setEnabled(true);
                        tvStatus.setText("状态: 已选择 PDF");
                    }
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnChoosePdf = findViewById(R.id.btnChoosePdf);
        btnPrintPdf = findViewById(R.id.btnPrintPdf);
        btnPrintSettings = findViewById(R.id.btnPrintSettings);
        btnWifiSettings = findViewById(R.id.btnWifiSettings);
        btnSelfCheck = findViewById(R.id.btnSelfCheck);
        tvStatus = findViewById(R.id.tvStatus);
        tvSelected = findViewById(R.id.tvSelected);
        rgPageMode = findViewById(R.id.rgPageMode);
        rbAll = findViewById(R.id.rbAll);
        rbOdd = findViewById(R.id.rbOdd);
        rbEven = findViewById(R.id.rbEven);

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
            int checkedId = rgPageMode.getCheckedRadioButtonId();
            if (checkedId == R.id.rbAll) {
                printAll(selectedPdfUri);
            } else if (checkedId == R.id.rbOdd) {
                printSelective(selectedPdfUri, SelectivePdfPrintAdapter.Mode.ODD);
            } else if (checkedId == R.id.rbEven) {
                printSelective(selectedPdfUri, SelectivePdfPrintAdapter.Mode.EVEN);
            }
        });

        btnPrintSettings.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_PRINT_SETTINGS)); }
            catch (Exception e) { appendStatus("无法打开打印设置，请在系统设置里搜索“打印”启用 华为/Mopria/Epson 插件"); }
        });

        btnWifiSettings.setOnClickListener(v -> {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startActivity(new Intent(Settings.Panel.ACTION_WIFI));
                } else {
                    startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                }
            } catch (Exception ignored) {}
        });

        btnSelfCheck.setOnClickListener(v -> {
            // 仅在自检时申请权限
            if (Build.VERSION.SDK_INT >= 23) {
                permissionLauncher.launch(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION
                });
            } else {
                doSelfCheck();
            }
        });

        btnPrintPdf.setEnabled(false);
        tvStatus.setText("状态: 就绪");
    }

    private void printAll(Uri uri) {
        try {
            ContentResolver resolver = getContentResolver();
            String jobName = "打印PDF（全部）";
            PrintManager printManager = (PrintManager) getSystemService(PRINT_SERVICE);
            PdfPrintAdapter adapter = new PdfPrintAdapter(this, resolver, uri, jobName);
            printManager.print(jobName, adapter, null);
        } catch (Exception e) {
            appendStatus("启动打印失败 - " + e.getMessage());
        }
    }

    private void printSelective(Uri uri, SelectivePdfPrintAdapter.Mode mode) {
        try {
            String jobName = mode == SelectivePdfPrintAdapter.Mode.ODD ? "打印PDF（仅奇数页）" : "打印PDF（仅偶数页）";
            PrintManager printManager = (PrintManager) getSystemService(PRINT_SERVICE);
            SelectivePdfPrintAdapter adapter = new SelectivePdfPrintAdapter(this, uri, jobName, mode);
            printManager.print(jobName, adapter, null);
        } catch (Exception e) {
            appendStatus("启动打印失败 - " + e.getMessage());
        }
    }

    private void doSelfCheck() {
        StringBuilder sb = new StringBuilder();
        // 1) SSID 检测（是否连到打印机热点）
        String ssid = NetUtils.getCurrentSsid(this);
        if (ssid == null) {
            sb.append("• 当前未能获取 SSID，请确认已授予定位权限且开启定位开关；建议在 Wi‑Fi 设置连接到打印机热点（通常以 DIRECT‑ 开头）。\n");
        } else {
            sb.append(String.format(Locale.CHINA, "• 当前已连接: %s\n", ssid));
            if (!(ssid.toLowerCase(Locale.ROOT).contains("epson") ||
                    ssid.toLowerCase(Locale.ROOT).contains("l8058") ||
                    ssid.toLowerCase(Locale.ROOT).startsWith("direct-"))) {
                sb.append("  建议连接到打印机热点（DIRECT‑xx‑EPSON/L8058）或将打印机加入同一路由网络。\n");
            }
        }

        // 2) 打印服务检测（是否安装/启用）
        List<String> services = PrintServiceChecker.listInstalledPrintServices(this);
        if (services.isEmpty()) {
            sb.append("• 未检测到已安装的打印服务。请安装并启用：华为打印服务插件 / Mopria Print Service / Epson Print Enabler。\n");
        } else {
            sb.append("• 已检测到打印服务: ").append(services).append("\n");
            sb.append("  如仍显示“该打印机目前无法使用”，请在“打印设置”中确保服务已启用。\n");
        }

        appendStatus(sb.toString());
    }

    private boolean areLocationPermissionsGranted(Map<String, Boolean> result) {
        Boolean fine = result.get(Manifest.permission.ACCESS_FINE_LOCATION);
        return Boolean.TRUE.equals(fine);
    }

    private void appendStatus(String msg) {
        String old = tvStatus.getText() != null ? tvStatus.getText().toString() : "";
        tvStatus.setText(old + (old.endsWith("\n") || old.isEmpty() ? "" : "\n") + msg);
    }
}
