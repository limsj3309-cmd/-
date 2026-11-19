package kr.golfmacro.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

final class Notifier {
    private static final String CH = "result";

    private static final String CH_STATUS = "status";
    private static final int ID_STATUS = 1;

    /** 상단 알림줄에 '자동 예약 켜짐 / 실행 중'을 늘 보여 준다. 꺼져 있으면 지운다. */
    static void updateStatus(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        Prefs p = new Prefs(c);
        boolean running = MacroService.runningNow();
        if (!running && !p.enabled()) {
            nm.cancel(ID_STATUS);
            return;
        }
        nm.createNotificationChannel(new NotificationChannel(CH_STATUS, "자동 예약 상태", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(c, 4, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(c, CH_STATUS)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open);
        if (running) {
            b.setContentTitle("⛳ 골프 예약 실행 중")
                    .setContentText(MacroService.runLabel() + " — 멈추려면 '지금 멈추기'")
                    .addAction(new Notification.Action.Builder(null, "지금 멈추기",
                            StopReceiver.pi(c, StopReceiver.ACTION_STOP_RUN)).build());
        } else {
            String next = p.nextRun() > 0 ? MainActivity.fmt(p.nextRun()) : "(알람 권한 확인 필요)";
            b.setContentTitle("⛳ 자동 예약 켜짐")
                    .setContentText("다음 실행 " + next + " · " + p.targetTime() + " · " + p.priority() + "번 순")
                    .addAction(new Notification.Action.Builder(null, "자동 예약 끄기",
                            StopReceiver.pi(c, StopReceiver.ACTION_DISABLE)).build());
        }
        nm.notify(ID_STATUS, b.build());
    }

    static void show(Context c, String title, String text) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CH, "예약 결과", NotificationManager.IMPORTANCE_HIGH));
        PendingIntent pi = PendingIntent.getActivity(c, 3, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CH)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify((int) (System.currentTimeMillis() % 100000), n);
    }
}
