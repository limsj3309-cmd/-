package kr.golfmacro.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 알림의 '지금 멈추기' / '자동 예약 끄기' 버튼. */
public class StopReceiver extends BroadcastReceiver {
    static final String ACTION_STOP_RUN = "kr.golfmacro.app.STOP_RUN";
    static final String ACTION_DISABLE = "kr.golfmacro.app.DISABLE";

    static PendingIntent pi(Context c, String action) {
        Intent i = new Intent(c, StopReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(c, action.hashCode(), i, PendingIntent.FLAG_IMMUTABLE);
    }

    static void stopRunNow(Context c) {
        MacroService s = MacroService.instance;
        if (s != null && s.isRunning()) {
            s.stopRun();
            L.i(c, "■ 사용자가 실행을 멈췄습니다.");
        }
    }

    static void disable(Context c) {
        stopRunNow(c);
        new Prefs(c).setEnabled(false);
        Scheduler.schedule(c);
        L.i(c, "⏸ 자동 예약을 껐습니다.");
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        if (ACTION_STOP_RUN.equals(intent.getAction())) stopRunNow(c);
        else if (ACTION_DISABLE.equals(intent.getAction())) disable(c);
        Notifier.updateStatus(c);
    }
}
