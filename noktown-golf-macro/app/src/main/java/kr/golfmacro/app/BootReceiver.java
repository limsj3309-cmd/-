package kr.golfmacro.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 재부팅/업데이트/시간 변경 후 알람을 다시 건다. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        long t = Scheduler.schedule(c);
        L.i(c, "알람 재설정 (" + intent.getAction() + "): " + (t == 0 ? "꺼짐" : MainActivity.fmt(t)));
    }
}
