package com.limsj.docviewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.limsj.docviewer.core.Util;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_OPEN = 1;

    private final List<Recent.Item> items = new ArrayList<>();
    private BaseAdapter adapter;
    private TextView empty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cleanCache();
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int fg = night ? Color.rgb(230, 230, 230) : Color.rgb(30, 30, 30);
        int sub = night ? Color.rgb(160, 160, 160) : Color.rgb(110, 110, 110);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, 0);

        Button open = new Button(this);
        open.setText("문서 열기");
        open.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        open.setTextColor(Color.WHITE);
        open.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(27, 95, 191));
        bg.setCornerRadius(dp(14));
        open.setBackground(bg);
        open.setOnClickListener(v -> pick());
        root.addView(open, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        TextView formats = new TextView(this);
        formats.setText("PDF · 워드(doc/docx) · 엑셀(xls/xlsx) · 파워포인트(ppt/pptx) · 한글(hwp/hwpx)");
        formats.setTextColor(sub);
        formats.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        formats.setGravity(Gravity.CENTER);
        formats.setPadding(0, dp(8), 0, dp(16));
        root.addView(formats);

        TextView title = new TextView(this);
        title.setText("최근 문서");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(fg);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setPadding(0, 0, 0, dp(6));
        root.addView(title);

        empty = new TextView(this);
        empty.setText("최근에 연 문서가 없습니다.\n파일 관리자나 메신저에서 문서를 누르고 '문서뷰어'를 선택해도 됩니다.");
        empty.setTextColor(sub);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(40), 0, 0);
        root.addView(empty);

        ListView list = new ListView(this);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View cv, ViewGroup parent) {
                LinearLayout row;
                TextView badge, name, time;
                if (cv instanceof LinearLayout) {
                    row = (LinearLayout) cv;
                    badge = (TextView) row.getChildAt(0);
                    LinearLayout col = (LinearLayout) row.getChildAt(1);
                    name = (TextView) col.getChildAt(0);
                    time = (TextView) col.getChildAt(1);
                } else {
                    row = new LinearLayout(MainActivity.this);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, dp(10), 0, dp(10));
                    badge = new TextView(MainActivity.this);
                    badge.setGravity(Gravity.CENTER);
                    badge.setTextColor(Color.WHITE);
                    badge.setTypeface(Typeface.DEFAULT_BOLD);
                    badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                    row.addView(badge, new LinearLayout.LayoutParams(dp(44), dp(44)));
                    LinearLayout col = new LinearLayout(MainActivity.this);
                    col.setOrientation(LinearLayout.VERTICAL);
                    col.setPadding(dp(12), 0, 0, 0);
                    name = new TextView(MainActivity.this);
                    name.setTextColor(fg);
                    name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                    name.setSingleLine(true);
                    name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                    time = new TextView(MainActivity.this);
                    time.setTextColor(sub);
                    time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    col.addView(name);
                    col.addView(time);
                    row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                }
                Recent.Item it = items.get(i);
                String ext = Util.ext(it.name);
                badge.setText(ext.isEmpty() ? "DOC" : ext.toUpperCase(java.util.Locale.ROOT));
                GradientDrawable b = new GradientDrawable();
                b.setCornerRadius(dp(10));
                b.setColor(colorFor(ext));
                badge.setBackground(b);
                name.setText(it.name);
                time.setText(DateUtils.getRelativeTimeSpanString(it.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setOnItemClickListener((p, v, pos, id) -> openRecent(items.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            Recent.Item it = items.get(pos);
            new AlertDialog.Builder(this)
                    .setTitle(it.name)
                    .setItems(new String[]{"목록에서 삭제"}, (d, w) -> {
                        Recent.remove(this, it.uri);
                        refresh();
                    }).show();
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private static int colorFor(String ext) {
        switch (ext) {
            case "pdf": return Color.rgb(214, 48, 49);
            case "doc": case "docx": return Color.rgb(43, 87, 154);
            case "xls": case "xlsx": return Color.rgb(16, 124, 65);
            case "ppt": case "pptx": return Color.rgb(210, 71, 38);
            case "hwp": case "hwpx": return Color.rgb(0, 120, 200);
            default: return Color.GRAY;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        items.clear();
        items.addAll(Recent.load(this));
        adapter.notifyDataSetChanged();
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "최근 목록 지우기");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            Recent.clear(this);
            refresh();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_OPEN);
        } catch (Exception e) {
            Toast.makeText(this, "파일 선택기를 열 수 없습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_OPEN || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        openUri(uri);
    }

    private void openRecent(Recent.Item it) {
        openUri(Uri.parse(it.uri));
    }

    private void openUri(Uri uri) {
        Intent v = new Intent(this, ViewerActivity.class);
        v.setAction(Intent.ACTION_VIEW);
        v.setData(uri);
        v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(v);
    }

    /** Removes leftover temp copies from earlier sessions. */
    private void cleanCache() {
        try {
            File dir = new File(getCacheDir(), "open");
            File[] fs = dir.listFiles();
            if (fs == null) return;
            long cutoff = System.currentTimeMillis() - 6 * 3600_000L;
            for (File f : fs) if (f.lastModified() < cutoff) f.delete();
        } catch (Exception ignored) {
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
