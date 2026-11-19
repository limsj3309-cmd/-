package kr.golfmacro.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

final class Notifier {
    private static final String CH = "result";

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
