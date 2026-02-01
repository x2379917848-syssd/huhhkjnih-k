package com.pdf.reader;

import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
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

public class MainActivity extends AppCompatActivity {

    private Button btnChoosePdf;
    private Button btnPrintPdf;
    private Button btnPrintSettings;
    private TextView tvStatus;
    private TextView tvSelected;
    private RadioGroup rgPageMode;
    private RadioButton rbAll, rbOdd, rbEven;

    private Uri selectedPdfUri;

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
            catch (Exception e) { tvStatus.setText("状态: 无法打开打印设置，请在系统设置里搜索“打印”启用 华为/Mopria/Epson 服务"); }
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
            tvStatus.setText("状态: 启动打印失败 - " + e.getMessage());
        }
    }

    private void printSelective(Uri uri, SelectivePdfPrintAdapter.Mode mode) {
        try {
            String jobName = mode == SelectivePdfPrintAdapter.Mode.ODD ? "打印PDF（仅奇数页）" : "打印PDF（仅偶数页）";
            PrintManager printManager = (PrintManager) getSystemService(PRINT_SERVICE);
            SelectivePdfPrintAdapter adapter = new SelectivePdfPrintAdapter(this, uri, jobName, mode);
            printManager.print(jobName, adapter, null);
        } catch (Exception e) {
            tvStatus.setText("状态: 启动打印失败 - " + e.getMessage());
        }
    }
}
