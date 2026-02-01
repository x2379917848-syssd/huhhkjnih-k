package com.pdf.reader;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.print.PrintManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.pdf.reader.net.WifiConnector;
import com.pdf.reader.print.PdfPrintAdapter;

public class MainActivity extends AppCompatActivity {

    private EditText etSsid;
    private EditText etPassword;
    private Button btnConnect;
    private Button btnDisconnect;
    private Button btnChoosePdf;
    private Button btnPrintPdf;
    private Button btnPrintSettings;
    private TextView tvStatus;
    private TextView tvSelected;

    private WifiConnector wifiConnector;
    private Uri selectedPdfUri;

    private SharedPreferences prefs;

    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                startConnect();
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
        tvStatus = findViewById(R.id.tvStatus);
        tvSelected = findViewById(R.id.tvSelected);

        wifiConnector = new WifiConnector(this, new WifiConnector.Callback() {
            @Override public void onConnecting() { runOnUiThread(() -> tvStatus.setText("状态: 正在连接…")); }
            @Override public void onConnected() { runOnUiThread(() -> tvStatus.setText("状态: 已连接到打印机热点")); }
            @Override public void onDisconnected() { runOnUiThread(() -> tvStatus.setText("状态: 已断开连接")); }
            @Override public void onFailed(String reason) { runOnUiThread(() -> tvStatus.setText("状态: 连接失败 - " + reason)); }
        });

        etSsid.setText(prefs.getString("ssid", ""));
        etPassword.setText(prefs.getString("password", ""));

        btnConnect.setOnClickListener(v -> ensurePermissionAndConnect());
        btnDisconnect.setOnClickListener(v -> wifiConnector.disconnect());

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

    private void ensurePermissionAndConnect() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissionLauncher.launch(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES});
        } else {
            requestPermissionLauncher.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION});
        }
    }

    private void startConnect() {
        String ssid = etSsid.getText().toString().trim();
        String password = etPassword.getText().toString().trim();
        if (ssid.isEmpty()) {
            tvStatus.setText("状态: 请输入打印机热点 SSID");
            return;
        }
        prefs.edit().putString("ssid", ssid).putString("password", password).apply();
        wifiConnector.connect(ssid, password);
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
    protected void onDestroy() {
        super.onDestroy();
        wifiConnector.disconnect();
    }
}
