package com.limsj.docviewer;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import com.limsj.docviewer.core.Doc;
import com.limsj.docviewer.core.Html;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;

/** PDF pages rendered on demand by the system PdfRenderer and shown as lazy images. */
final class PdfDoc implements Doc.Res, Closeable {
    private final ParcelFileDescriptor fd;
    private final PdfRenderer renderer;
    private final int targetWidth;
    private boolean closed;

    private PdfDoc(File f, int targetWidth) throws IOException {
        fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer r;
        try {
            r = new PdfRenderer(fd);
        } catch (IOException | RuntimeException e) {
            fd.close();
            throw e;
        }
        renderer = r;
        this.targetWidth = targetWidth;
    }

    static Doc open(File f, int screenWidthPx) throws Exception {
        PdfDoc p;
        try {
            p = new PdfDoc(f, Math.max(800, Math.min(2000, (int) (screenWidthPx * 1.6))));
        } catch (SecurityException e) {
            throw new Doc.UserError("암호가 설정된 PDF는 열 수 없습니다.");
        } catch (IOException e) {
            throw new Doc.UserError("손상되었거나 지원하지 않는 PDF입니다.");
        }
        try {
            int n = p.renderer.getPageCount();
            StringBuilder sb = new StringBuilder(n * 160 + 512);
            for (int i = 0; i < n; i++) {
                int w, h;
                synchronized (p) {
                    PdfRenderer.Page page = p.renderer.openPage(i);
                    w = page.getWidth();
                    h = page.getHeight();
                    page.close();
                }
                if (w <= 0 || h <= 0) { w = 595; h = 842; }
                sb.append("<div class=\"pg\" style=\"aspect-ratio:").append(w).append('/').append(h)
                        .append("\"><img loading=\"lazy\" src=\"res/page/").append(i).append("\" alt=\"\"></div>")
                        .append("<div class=\"pn\">").append(i + 1).append(" / ").append(n).append("</div>");
            }
            if (n == 0) return new Doc(Html.message("빈 PDF", "페이지가 없습니다."), null, p);
            String css = "body{background:#525659}"
                    + ".pg{background:#fff;max-width:1200px;margin:8px auto 0;box-shadow:0 1px 6px rgba(0,0,0,.5)}"
                    + ".pg img{display:block;width:100%;height:100%}"
                    + ".pn{text-align:center;color:#ddd;font-size:11px;font-family:sans-serif;margin:3px 0}";
            return new Doc(Html.page("pdf", css, sb.toString(), null), p, p);
        } catch (Exception e) {
            p.close();
            throw e;
        }
    }

    @Override
    public byte[] get(String path) {
        if (!path.startsWith("page/")) return null;
        int idx;
        try {
            idx = Integer.parseInt(path.substring(5));
        } catch (NumberFormatException e) {
            return null;
        }
        Bitmap bmp = null;
        try {
            synchronized (this) {
                if (closed || idx < 0 || idx >= renderer.getPageCount()) return null;
                PdfRenderer.Page page = renderer.openPage(idx);
                try {
                    int pw = Math.max(1, page.getWidth()), ph = Math.max(1, page.getHeight());
                    int w = targetWidth;
                    int h = (int) ((long) w * ph / pw);
                    if (h > 6000) { // very tall pages: cap memory
                        h = 6000;
                        w = (int) ((long) h * pw / ph);
                    }
                    bmp = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(Color.WHITE);
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                } finally {
                    page.close();
                }
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream(256 * 1024);
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, bo);
            return bo.toByteArray();
        } catch (OutOfMemoryError | RuntimeException e) {
            return null;
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            renderer.close();
        } catch (Exception ignored) {
        }
        try {
            fd.close();
        } catch (Exception ignored) {
        }
    }
}
