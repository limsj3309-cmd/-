package kr.golfmacro.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 설정 화면 (코드로 만든 단순한 화면). */
public class MainActivity extends Activity {

    private TextView status, logView;
    private Switch enabledSw;
    private EditText targetEt, openEt, leadEt, delayEt, testEt;

    static String fmt(long t) {
        return new SimpleDateFormat("M/d(E) HH:mm:ss", Locale.KOREA).format(new Date(t));
    }

    static String fmtMs(long t) {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.KOREA).format(new Date(t));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs p = new Prefs(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad * 2);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        status.setBackgroundColor(Color.parseColor("#EEF3FF"));
        root.addView(status, lp());

        header(root, "① 자동 예약 설정");
        enabledSw = new Switch(this);
        enabledSw.setText("매일 자동 예약 켜기");
        enabledSw.setTextSize(17);
        enabledSw.setChecked(p.enabled());
        enabledSw.setPadding(0, dp(8), 0, dp(8));
        root.addView(enabledSw, lp());
        targetEt = field(root, "예약할 시간 칸 (예: 20:00)", p.targetTime(), InputType.TYPE_CLASS_DATETIME);
        openEt = field(root, "예약이 열리는 시각 (예: 06:00:00)", p.openTime(), InputType.TYPE_CLASS_DATETIME);
        leadEt = field(root, "몇 초 전에 미리 앱 열기 (기본 60)", String.valueOf(p.leadSec()), InputType.TYPE_CLASS_NUMBER);
        delayEt = field(root, "오픈 후 몇 ms 뒤 새로고침 (기본 150)", String.valueOf(p.clickDelayMs()), InputType.TYPE_CLASS_NUMBER);
        button(root, "저장", v -> save(true));
        enabledSw.setOnCheckedChangeListener((CompoundButton bt, boolean on) -> save(false));

        header(root, "② 처음 한 번 권한 설정");
        button(root, "접근성 설정 열기 (골프예약 매크로 켜기)", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        button(root, "앱 정보 열기 (제한된 설정 허용)", v ->
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))));
        button(root, "배터리 최적화에서 빼기", v -> askBattery());
        button(root, "알림 허용", v -> askNotify());

        header(root, "③ 테스트");
        testEt = field(root, "테스트할 시간 칸 (지금 남아 있는 시간, 예: 21:00)", p.testTime(), InputType.TYPE_CLASS_DATETIME);
        button(root, "테스트: 시간 칸 선택까지만 (예약 안 함)", v -> runTest(true));
        button(root, "테스트: 실제로 예약까지", v -> new AlertDialog.Builder(this)
                .setTitle("실제 예약")
                .setMessage(testEt.getText() + " 칸을 실제로 예약합니다. 계속할까요?")
                .setPositiveButton("예약", (d, w) -> runTest(false))
                .setNegativeButton("취소", null)
                .show());
        button(root, "10초 뒤 화면 구조 저장", v -> dumpLater());
        button(root, "📤 문제 보고 공유 (기록 + 화면 구조)", v -> shareReport());

        header(root, "기록");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        smallButton(row, "새로고침", v -> refreshLog());
        smallButton(row, "공유", v -> shareText(L.read(this), "로그"));
        smallButton(row, "지우기", v -> { L.clear(this); refreshLog(); });
        root.addView(row, lp());
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(8), dp(8), dp(8), dp(8));
        logView.setBackgroundColor(Color.parseColor("#F4F4F4"));
        root.addView(logView, lp());

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
        setTitle("골프예약 매크로");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshLog();
    }

    private void save(boolean toast) {
        String target = Prefs.normHm(targetEt.getText().toString());
        int[] open = Prefs.parseHms(openEt.getText().toString());
        String test = Prefs.normHm(testEt.getText().toString());
        int lead = parseInt(leadEt.getText().toString(), 60);
        int delay = parseInt(delayEt.getText().toString(), 150);
        if (target == null || open == null || test == null) {
            Toast.makeText(this, "시간 형식이 잘못되었습니다. 예: 20:00 / 06:00:00", Toast.LENGTH_LONG).show();
            return;
        }
        lead = Math.max(20, Math.min(600, lead));
        delay = Math.max(0, Math.min(5000, delay));
        String openStr = String.format(Locale.US, "%02d:%02d:%02d", open[0], open[1], open[2]);
        new Prefs(this).save(enabledSw.isChecked(), target, openStr, lead, delay, test);
        targetEt.setText(target);
        openEt.setText(openStr);
        leadEt.setText(String.valueOf(lead));
        delayEt.setText(String.valueOf(delay));
        testEt.setText(test);

        long t = Scheduler.schedule(this);
        L.i(this, "설정 저장: 켜짐=" + enabledSw.isChecked() + ", 시간칸 " + target + ", 오픈 " + openStr
                + ", 다음 실행 " + (t == 0 ? "없음" : fmt(t)));
        if (toast) Toast.makeText(this, "저장했습니다", Toast.LENGTH_SHORT).show();
        refreshStatus();
        refreshLog();
    }

    private void runTest(boolean dry) {
        save(false);
        MacroService s = MacroService.instance;
        if (s == null) {
            Toast.makeText(this, "먼저 접근성 설정에서 '골프예약 매크로'를 켜 주세요.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (s.isRunning()) s.stopRun();
        s.startRun(System.currentTimeMillis(), new Prefs(this).testTime(), dry, dry ? "테스트(선택까지)" : "테스트(실제 예약)");
        MacroService.launchNoktown(this);
    }

    private void dumpLater() {
        MacroService s = MacroService.instance;
        if (s == null) {
            Toast.makeText(this, "먼저 접근성 서비스를 켜 주세요.", Toast.LENGTH_LONG).show();
            return;
        }
        s.dumpLater(10_000);
        Toast.makeText(this, "10초 안에 노크타운의 원하는 화면을 띄워 두세요.", Toast.LENGTH_LONG).show();
    }

    private void refreshStatus() {
        Prefs p = new Prefs(this);
        boolean svc = MacroService.instance != null || accessibilityOn();
        boolean nk = getPackageManager().getLaunchIntentForPackage(MacroService.PKG) != null;
        PowerManager pm = getSystemService(PowerManager.class);
        boolean batt = pm.isIgnoringBatteryOptimizations(getPackageName());
        StringBuilder sb = new StringBuilder();
        sb.append(svc ? "✅ 접근성 서비스 켜짐\n" : "❌ 접근성 서비스 꺼짐 → ②에서 켜 주세요\n");
        sb.append(nk ? "✅ 노크타운 앱 확인\n" : "❌ 노크타운 앱을 찾을 수 없음\n");
        sb.append(batt ? "✅ 배터리 최적화 제외됨\n" : "⚠️ 배터리 최적화 중 (제외 권장)\n");
        if (!Scheduler.canExact(this)) sb.append("❌ 정확한 알람 권한 없음\n");
        if (p.enabled() && p.nextRun() > 0) {
            sb.append("⏰ 다음 실행: ").append(fmt(p.nextRun()))
                    .append("\n   → ").append(p.openTime()).append(" 에 ").append(p.targetTime()).append(" 칸 예약");
        } else {
            sb.append("⏸ 자동 예약 꺼짐");
        }
        status.setText(sb.toString());
    }

    private boolean accessibilityOn() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String me = new ComponentName(this, MacroService.class).flattenToString();
        return s != null && s.contains(me);
    }

    private void refreshLog() {
        String all = L.read(this);
        String[] lines = all.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = lines.length - 1, n = 0; i >= 0 && n < 120; i--, n++) sb.append(lines[i]).append('\n');
        logView.setText(sb.length() == 0 ? "(기록 없음)" : sb.toString());
    }

    private void askBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void askNotify() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else {
            Toast.makeText(this, "이미 허용되어 있습니다", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareReport() {
        String log = L.read(this);
        if (log.length() > 15_000) log = log.substring(log.length() - 15_000);
        String dump = L.readFile(new File(getFilesDir(), "dump.txt"));
        if (dump.length() > 60_000) dump = dump.substring(0, 60_000) + "\n...(생략)";
        String ver;
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            ver = "?";
        }
        shareText("[골프예약 매크로 v" + ver + " 문제 보고]\n\n=== 기록 ===\n" + log
                + "\n\n=== 화면 구조 ===\n" + (dump.isEmpty() ? "(없음)" : dump), "문제 보고");
    }

    private void shareFile(File f, String title) {
        String s = L.readFile(f);
        if (s.isEmpty()) {
            Toast.makeText(this, "저장된 " + title + "가 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        shareText(s, title);
    }

    private void shareText(String s, String title) {
        if (s.length() > 90_000) s = s.substring(s.length() - 90_000);
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s);
        startActivity(Intent.createChooser(i, title + " 공유"));
    }

    // ---------------------------------------------------------------- UI helpers

    private void header(LinearLayout root, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(24), 0, dp(6));
        root.addView(t, lp());
    }

    private EditText field(LinearLayout root, String label, String value, int type) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        t.setPadding(0, dp(8), 0, 0);
        root.addView(t, lp());
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(type);
        e.setSingleLine(true);
        root.addView(e, lp());
        return e;
    }

    private void button(LinearLayout root, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        root.addView(b, lp());
    }

    private void smallButton(LinearLayout row, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        b.setGravity(Gravity.CENTER);
        row.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    }

    private LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
