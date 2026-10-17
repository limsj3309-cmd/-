package com.limsj.docviewer;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.limsj.docviewer.core.Converter;
import com.limsj.docviewer.core.Doc;
import com.limsj.docviewer.core.Html;
import com.limsj.docviewer.core.Util;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ViewerActivity extends Activity {
    private static final String HOST = "doc.local";
    private static final String BASE = "https://" + HOST + "/";
    private static final int[] ZOOMS = {75, 90, 100, 115, 130, 150, 175, 200};

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private WebView web;
    private ProgressBar progress;
    private TextView status;
    private LinearLayout findBar;
    private EditText findText;
    private TextView findCount;

    private volatile Doc doc;
    private volatile String html;
    private File temp;
    private Uri uri;
    private String name = "문서";
    private Converter.Kind kind = Converter.Kind.UNKNOWN;
    private volatile boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActionBar ab = getActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
            ab.setTitle("문서 여는 중…");
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        findBar = buildFindBar();
        findBar.setVisibility(View.GONE);
        root.addView(findBar);

        FrameLayout frame = new FrameLayout(this);
        try {
            web = new WebView(this);
        } catch (Throwable t) {
            // WebView missing/updating on the device
            TextView tv = new TextView(this);
            tv.setText("시스템 WebView를 사용할 수 없습니다.\nPlay 스토어에서 'Android System WebView'를 업데이트해 주세요.");
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(24), dp(24), dp(24), dp(24));
            setContentView(tv);
            return;
        }
        setupWeb();
        frame.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout loading = new LinearLayout(this);
        loading.setOrientation(LinearLayout.VERTICAL);
        loading.setGravity(Gravity.CENTER);
        progress = new ProgressBar(this);
        loading.addView(progress);
        status = new TextView(this);
        status.setText("문서를 여는 중입니다…");
        status.setPadding(0, dp(12), 0, 0);
        loading.addView(status);
        frame.addView(loading, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (web == null) return;
        releaseDoc();
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        uri = null;
        if (intent != null) {
            uri = intent.getData();
            if (uri == null && Intent.ACTION_SEND.equals(intent.getAction())) {
                Parcelable p = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (p instanceof Uri) uri = (Uri) p;
            }
        }
        if (uri == null) {
            showError("파일 없음", "열 문서가 지정되지 않았습니다.");
            return;
        }
        setLoading(true, "문서를 여는 중입니다…");
        final Uri u = uri;
        final int screenW = getResources().getDisplayMetrics().widthPixels;
        worker.execute(() -> load(u, screenW));
    }

    // ---------- loading (background thread) ----------

    private void load(Uri u, int screenW) {
        Doc result = null;
        String errTitle = null, errMsg = null;
        try {
            String n = displayName(u);
            name = n;
            main.post(() -> {
                ActionBar ab = getActionBar();
                if (ab != null) ab.setTitle(name);
            });
            File f = copyToCache(u, n);
            temp = f;
            kind = Converter.detect(f, n);
            if (kind == Converter.Kind.UNKNOWN) {
                if (Converter.isEncryptedOoxml(f)) throw new Doc.UserError("암호가 설정된 문서는 열 수 없습니다.");
                throw new Doc.UserError("PDF, 워드, 엑셀, 파워포인트, 한글 문서만 열 수 있습니다.");
            }
            if (kind == Converter.Kind.PDF) result = PdfDoc.open(f, screenW);
            else result = Converter.convert(f, kind);
            try {
                Recent.add(this, u.toString(), n);
            } catch (Exception ignored) {
            }
        } catch (Doc.UserError e) {
            errTitle = "열 수 없는 문서";
            errMsg = e.getMessage();
        } catch (SecurityException e) {
            errTitle = "접근 권한 없음";
            errMsg = "이 파일을 읽을 권한이 없습니다. 파일을 다시 선택해 주세요.";
        } catch (java.io.FileNotFoundException e) {
            errTitle = "파일을 찾을 수 없음";
            errMsg = "파일이 이동되었거나 삭제되었습니다.";
        } catch (OutOfMemoryError e) {
            errTitle = "문서가 너무 큽니다";
            errMsg = "이 기기에서 표시하기에 문서가 너무 큽니다.";
        } catch (Throwable t) {
            errTitle = "문서를 열 수 없습니다";
            errMsg = "파일이 손상되었거나 지원하지 않는 형식입니다.\n(" + t.getClass().getSimpleName() + ")";
        }
        final Doc d = result;
        final String et = errTitle, em = errMsg;
        main.post(() -> {
            if (destroyed) {
                if (d != null) d.close();
                return;
            }
            if (d != null) show(d);
            else showError(et, em);
        });
    }

    private String displayName(Uri u) {
        String n = null;
        if ("content".equals(u.getScheme())) {
            try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) n = c.getString(0);
            } catch (Exception ignored) {
            }
        }
        if (n == null) {
            n = u.getLastPathSegment();
            if (n != null) {
                int s = n.lastIndexOf('/');
                if (s >= 0) n = n.substring(s + 1);
            }
        }
        return n == null || n.isEmpty() ? "문서" : n;
    }

    private File copyToCache(Uri u, String n) throws Exception {
        File dir = new File(getCacheDir(), "open");
        if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("cache");
        String ext = Util.ext(n);
        File f = new File(dir, System.currentTimeMillis() + "_" + Math.abs(u.hashCode()) + (ext.isEmpty() ? "" : "." + ext));
        try (InputStream in = getContentResolver().openInputStream(u); OutputStream out = new FileOutputStream(f)) {
            if (in == null) throw new java.io.FileNotFoundException();
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) {
                if (destroyed) throw new InterruptedException();
                out.write(buf, 0, r);
            }
        }
        return f;
    }

    // ---------- UI ----------

    private void show(Doc d) {
        doc = d;
        html = d.html;
        applyZoom();
        web.loadUrl(BASE + "index.html");
        setLoading(false, null);
        invalidateOptionsMenu();
    }

    private void showError(String title, String msg) {
        html = Html.message(title, msg);
        doc = null;
        if (web != null) web.loadUrl(BASE + "index.html");
        setLoading(false, null);
    }

    private void setLoading(boolean on, String text) {
        if (progress == null) return;
        View container = (View) progress.getParent();
        container.setVisibility(on ? View.VISIBLE : View.GONE);
        if (text != null) status.setText(text);
    }

    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setDomStorageEnabled(false);
        s.setGeolocationEnabled(false);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setSaveFormData(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        web.setBackgroundColor(Color.rgb(232, 233, 237));
        web.setWebViewClient(new Client());
        web.setFindListener((active, count, done) -> {
            if (findCount != null && done) findCount.setText(count == 0 ? "0/0" : (active + 1) + "/" + count);
        });
    }

    private final class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            return intercept(req.getUrl());
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (HOST.equals(u.getHost())) return false;
            String sch = u.getScheme();
            if ("http".equals(sch) || "https".equals(sch) || "mailto".equals(sch) || "tel".equals(sch)) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
            }
            return true;
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // Never let a WebView renderer crash take the app down.
            try {
                ViewGroup parent = (ViewGroup) web.getParent();
                if (parent != null) parent.removeView(web);
                web.destroy();
            } catch (Exception ignored) {
            }
            web = null;
            if (!destroyed) {
                Toast.makeText(ViewerActivity.this, "문서 표시 중 메모리가 부족했습니다.", Toast.LENGTH_LONG).show();
                finish();
            }
            return true;
        }
    }

    private WebResourceResponse intercept(Uri u) {
        try {
            if (!HOST.equals(u.getHost())) return empty(404);
            String path = u.getPath() == null ? "" : u.getPath();
            if (path.equals("/index.html")) {
                String h = html;
                if (h == null) h = Html.message("", "");
                return ok("text/html", h.getBytes("UTF-8"));
            }
            if (path.startsWith("/res/")) {
                Doc d = doc;
                if (d == null || d.res == null) return empty(404);
                String p = URLDecoder.decode(u.getEncodedPath().substring(5), "UTF-8");
                byte[] b = d.res.get(p);
                if (b == null) return empty(404);
                String mime = Util.sniffImage(b);
                if (mime == null) mime = Util.imageMime(p);
                if (mime == null) mime = "application/octet-stream";
                return ok(mime, b);
            }
        } catch (Throwable ignored) {
        }
        return empty(404);
    }

    private static WebResourceResponse ok(String mime, byte[] b) {
        WebResourceResponse r = new WebResourceResponse(mime, "text/html".equals(mime) ? "utf-8" : null, new ByteArrayInputStream(b));
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        r.setResponseHeaders(h);
        return r;
    }

    private static WebResourceResponse empty(int code) {
        return new WebResourceResponse("text/plain", "utf-8", code, "Not Found", new HashMap<>(), new ByteArrayInputStream(new byte[0]));
    }

    // ---------- find bar ----------

    private LinearLayout buildFindBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(4), dp(4), dp(4));
        findText = new EditText(this);
        findText.setHint("문서에서 찾기");
        findText.setSingleLine(true);
        findText.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        findText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                if (web != null) web.findAllAsync(e.toString());
            }
        });
        findText.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                if (web != null) web.findNext(true);
                return true;
            }
            return false;
        });
        bar.addView(findText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        findCount = new TextView(this);
        findCount.setPadding(dp(6), 0, dp(6), 0);
        findCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        bar.addView(findCount);
        bar.addView(smallButton("▲", v -> { if (web != null) web.findNext(false); }));
        bar.addView(smallButton("▼", v -> { if (web != null) web.findNext(true); }));
        bar.addView(smallButton("✕", v -> closeFind()));
        return bar;
    }

    private Button smallButton(String t, View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(t);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setOnClickListener(l);
        return b;
    }

    private void openFind() {
        findBar.setVisibility(View.VISIBLE);
        findText.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(findText, InputMethodManager.SHOW_IMPLICIT);
    }

    private void closeFind() {
        findBar.setVisibility(View.GONE);
        if (web != null) web.clearMatches();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(findText.getWindowToken(), 0);
    }

    // ---------- menu ----------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "찾기").setIcon(android.R.drawable.ic_menu_search).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(0, 2, 1, "글자 크기");
        menu.add(0, 3, 2, "맨 위로");
        menu.add(0, 4, 3, "다른 앱으로 열기");
        menu.add(0, 5, 4, "공유");
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem z = menu.findItem(2);
        if (z != null) z.setVisible(kind != Converter.Kind.PDF);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home: finish(); return true;
            case 1: if (findBar.getVisibility() == View.VISIBLE) closeFind(); else openFind(); return true;
            case 2: chooseZoom(); return true;
            case 3: if (web != null) { web.pageUp(true); web.scrollTo(0, 0); } return true;
            case 4: external(Intent.ACTION_VIEW); return true;
            case 5: external(Intent.ACTION_SEND); return true;
            default: return super.onOptionsItemSelected(item);
        }
    }

    private void external(String action) {
        if (uri == null) return;
        try {
            Intent i = new Intent(action);
            String type = getContentResolver().getType(uri);
            if (type == null) type = "application/octet-stream";
            if (Intent.ACTION_SEND.equals(action)) {
                i.setType(type);
                i.putExtra(Intent.EXTRA_STREAM, uri);
            } else i.setDataAndType(uri, type);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, null));
        } catch (Exception e) {
            Toast.makeText(this, "열 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("viewer", MODE_PRIVATE);
    }

    private void chooseZoom() {
        String[] labels = new String[ZOOMS.length];
        int cur = prefs().getInt("zoom", 100), sel = 2;
        for (int i = 0; i < ZOOMS.length; i++) {
            labels[i] = ZOOMS[i] + "%";
            if (ZOOMS[i] == cur) sel = i;
        }
        new AlertDialog.Builder(this).setTitle("글자 크기")
                .setSingleChoiceItems(labels, sel, (dlg, w) -> {
                    prefs().edit().putInt("zoom", ZOOMS[w]).apply();
                    applyZoom();
                    dlg.dismiss();
                }).show();
    }

    private void applyZoom() {
        if (web == null) return;
        int z = kind == Converter.Kind.PDF ? 100 : prefs().getInt("zoom", 100);
        web.getSettings().setTextZoom(z);
    }

    @Override
    public void onBackPressed() {
        if (findBar != null && findBar.getVisibility() == View.VISIBLE) {
            closeFind();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (web != null && level >= TRIM_MEMORY_RUNNING_LOW) web.freeMemory();
    }

    private void releaseDoc() {
        final Doc d = doc;
        doc = null;
        final File t = temp;
        temp = null;
        worker.execute(() -> {
            if (d != null) d.close();
            if (t != null) t.delete();
        });
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (web != null) {
            try {
                web.stopLoading();
                ViewGroup parent = (ViewGroup) web.getParent();
                if (parent != null) parent.removeView(web);
                web.destroy();
            } catch (Exception ignored) {
            }
            web = null;
        }
        releaseDoc();
        worker.shutdown();
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
