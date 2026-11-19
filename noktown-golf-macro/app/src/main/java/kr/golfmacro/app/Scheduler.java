package kr.golfmacro.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

/** 매일 (예약 오픈 시각 - 미리 시작 초) 에 AlarmActivity 를 깨운다. */
final class Scheduler {

    /** 지금 기준 가장 가까운 '오픈 시각'(ms). 5분 넘게 지났으면 다음 날. */
    static long openTimeNear(Context c, long now) {
        Prefs p = new Prefs(c);
        int[] hms = Prefs.parseHms(p.openTime());
        if (hms == null) hms = new int[]{6, 0, 0};
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, hms[0]);
        cal.set(Calendar.MINUTE, hms[1]);
        cal.set(Calendar.SECOND, hms[2]);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() < now - 5 * 60_000L) cal.add(Calendar.DAY_OF_YEAR, 1);
        return cal.getTimeInMillis();
    }

    static long nextTrigger(Context c, long now) {
        Prefs p = new Prefs(c);
        int[] hms = Prefs.parseHms(p.openTime());
        if (hms == null) hms = new int[]{6, 0, 0};
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, hms[0]);
        cal.set(Calendar.MINUTE, hms[1]);
        cal.set(Calendar.SECOND, hms[2]);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.SECOND, -p.leadSec());
        while (cal.getTimeInMillis() <= now + 5_000L) cal.add(Calendar.DAY_OF_YEAR, 1);
        return cal.getTimeInMillis();
    }

    static boolean canExact(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager am = c.getSystemService(AlarmManager.class);
        return am.canScheduleExactAlarms();
    }

    private static PendingIntent op(Context c) {
        Intent i = new Intent(c, AlarmActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 1, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** @return 예약된 시각, 꺼져 있거나 실패하면 0 */
    static long schedule(Context c) {
        Prefs p = new Prefs(c);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent op = op(c);
        if (!p.enabled()) {
            am.cancel(op);
            p.setNextRun(0);
            return 0;
        }
        if (!canExact(c)) {
            L.i(c, "정확한 알람 권한이 없어 예약하지 못했습니다.");
            p.setNextRun(0);
            return 0;
        }
        long t = nextTrigger(c, System.currentTimeMillis());
        PendingIntent show = PendingIntent.getActivity(c, 2,
                new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(t, show), op);
        p.setNextRun(t);
        return t;
    }
}
