package com.pdf.reader.print;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 全部页面直传原始 PDF，保证质量（不渲染）。
 */
public class PdfPrintAdapter extends PrintDocumentAdapter {

    private final Context context;
    private final ContentResolver resolver;
    private final Uri pdfUri;
    private final String jobName;

    public PdfPrintAdapter(Context context, ContentResolver resolver, Uri pdfUri, String jobName) {
        this.context = context;
        this.resolver = resolver;
        this.pdfUri = pdfUri;
        this.jobName = jobName;
    }

    @Override
    public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                         CancellationSignal cancellationSignal,
                         LayoutResultCallback callback, android.os.Bundle extras) {
        if (cancellationSignal.isCanceled()) {
            callback.onLayoutCancelled();
            return;
        }
        PrintDocumentInfo info = new PrintDocumentInfo.Builder(jobName)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .build();
        callback.onLayoutFinished(info, true);
    }

    @Override
    public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                        CancellationSignal cancellationSignal, WriteResultCallback callback) {
        try (InputStream in = resolver.openInputStream(pdfUri);
             OutputStream out = new FileOutputStream(destination.getFileDescriptor())) {

            if (in == null) {
                callback.onWriteFailed("无法打开 PDF");
                return;
            }

            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (cancellationSignal.isCanceled()) {
                    callback.onWriteCancelled();
                    return;
                }
                out.write(buffer, 0, read);
            }
            out.flush();
            callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
        } catch (Exception e) {
            callback.onWriteFailed("写入失败: " + e.getMessage());
        }
    }
}
