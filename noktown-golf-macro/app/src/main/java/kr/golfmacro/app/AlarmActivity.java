package kr.golfmacro.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Bundle;
import android.view.WindowManager;

/** 알람 시각에 화면을 켜고, 노크타운을 띄운 뒤 자동 예약을 시작한다. */
public class AlarmActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km != null) km.requestDismissKeyguard(this, null);

        long now = System.currentTimeMillis();
        Prefs p = new Prefs(this);
        long next = Scheduler.schedule(this); // 내일 것 미리 걸기
        L.i(this, "⏰ 알람 실행. 다음 알람: " + (next == 0 ? "없음" : MainActivity.fmt(next)));

        MacroService s = MacroService.instance;
        if (s == null) {
            L.i(this, "접근성 서비스가 꺼져 있어 실행하지 못했습니다.");
            Notifier.show(this, "자동 예약 실패", "접근성 서비스가 꺼져 있습니다. 앱에서 다시 켜 주세요.");
        } else {
            long openAt = Scheduler.openTimeNear(this, now);
            s.startRun(openAt, p.targetTime(), false, true, "매일 자동 예약");
            MacroService.launchNoktown(this);
        }
        finish();
    }
}
