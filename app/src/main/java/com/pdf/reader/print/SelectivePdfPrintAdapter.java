package com.pdf.reader.print;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.pdf.PrintedPdfDocument;
import android.net.Uri;

import java.io.FileOutputStream;

/**
 * 仅奇数页/仅偶数页打印：用 PdfRenderer 渲染到 PrintedPdfDocument，再输出。
 * 注意：为兼顾内存与质量，默认按 200 DPI 渲染；若需要更高质量可调高 DPI。
 */
public class SelectivePdfPrintAdapter extends PrintDocumentAdapter {

    public enum Mode { ODD, EVEN }

    private static final int RENDER_DPI = 200;

    private final Context context;
    private final Uri pdfUri;
    private final String jobName;
    private final Mode mode;

    private int pageCount = 0;

    public SelectivePdfPrintAdapter(Context context, Uri pdfUri, String jobName, Mode mode) {
        this.context = context.getApplicationContext();
        this.pdfUri = pdfUri;
        this.jobName = jobName;
        this.mode = mode;
    }

    @Override
    public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                         CancellationSignal cancellationSignal,
                         LayoutResultCallback callback, android.os.Bundle extras) {
        if (cancellationSignal.isCanceled()) {
            callback.onLayoutCancelled();
            return;
        }
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(pdfUri, "r");
             PdfRenderer renderer = (pfd != null ? new PdfRenderer(pfd) : null)) {

            pageCount = (renderer != null) ? renderer.getPageCount() : 0;

            PrintDocumentInfo info = new PrintDocumentInfo.Builder(jobName)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(pageCount)
                    .build();
            callback.onLayoutFinished(info, true);
        } catch (Exception e) {
            callback.onLayoutFailed("分析 PDF 失败: " + e.getMessage());
        }
    }

    @Override
    public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                        CancellationSignal cancellationSignal, WriteResultCallback callback) {
        PrintedPdfDocument outDoc = null;
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(pdfUri, "r");
             PdfRenderer renderer = (pfd != null ? new PdfRenderer(pfd) : null);
             FileOutputStream fos = new FileOutputStream(destination.getFileDescriptor())) {

            if (renderer == null) {
                callback.onWriteFailed("无法打开 PDF");
                return;
            }

            outDoc = new PrintedPdfDocument(context, getBestAttributesForOutput());

            // 计算纸张尺寸（以 points 计，1 point=1/72 inch）
            PrintAttributes.MediaSize media = getBestAttributesForOutput().getMediaSize();
            int pageWidthPts = (int) Math.round(media.getWidthMils() * 72.0 / 1000.0);
            int pageHeightPts = (int) Math.round(media.getHeightMils() * 72.0 / 1000.0);

            for (int i = 0; i < renderer.getPageCount(); i++) {
                if (cancellationSignal.isCanceled()) {
                    callback.onWriteCancelled();
                    return;
                }
                int pageNumberHuman = i + 1;
                boolean pick = (mode == Mode.ODD) ? (pageNumberHuman % 2 == 1)
                        : (pageNumberHuman % 2 == 0);
                if (!pick) continue;

                PdfRenderer.Page srcPage = renderer.openPage(i);
                try {
                    PrintedPdfDocument.PageInfo pageInfo = new PrintedPdfDocument.PageInfo.Builder(
                            pageWidthPts, pageHeightPts, pageNumberHuman).create();
                    PrintedPdfDocument.Page dstPage = outDoc.startPage(pageInfo);

                    // 将 PDF 页面渲染成位图（按目标纸张尺寸的 DPI）
                    int bmpW = (int) Math.ceil(pageWidthPts / 72f * RENDER_DPI);
                    int bmpH = (int) Math.ceil(pageHeightPts / 72f * RENDER_DPI);
                    Bitmap bmp = Bitmap.createBitmap(Math.max(1, bmpW), Math.max(1, bmpH), Bitmap.Config.ARGB_8888);

                    srcPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);

                    // 等比缩放居中绘制到目标页
                    Canvas canvas = dstPage.getCanvas();
                    float scale = Math.min(
                            canvas.getWidth() / (float) bmp.getWidth(),
                            canvas.getHeight() / (float) bmp.getHeight()
                    );
                    Matrix m = new Matrix();
                    float dx = (canvas.getWidth() - bmp.getWidth() * scale) / 2f;
                    float dy = (canvas.getHeight() - bmp.getHeight() * scale) / 2f;
                    m.postScale(scale, scale);
                    m.postTranslate(dx, dy);
                    canvas.drawBitmap(bmp, m, null);

                    outDoc.finishPage(dstPage);
                    bmp.recycle();
                } finally {
                    srcPage.close();
                }
            }

            outDoc.writeTo(fos);
            callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
        } catch (Exception e) {
            callback.onWriteFailed("写入失败: " + e.getMessage());
        } finally {
            if (outDoc != null) outDoc.close();
        }
    }

    // 直接使用系统传入的 attributes 会有机型兼容差异，这里按常见设置回退
    private PrintAttributes getBestAttributesForOutput() {
        PrintAttributes.Builder b = new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .setResolution(new PrintAttributes.Resolution("pdf", "pdf", RENDER_DPI, RENDER_DPI))
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS);
        return b.build();
    }
}
