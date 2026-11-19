package kr.golfmacro.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 앱 안에서 볼 수 있는 간단한 파일 로그. */
final class L {
    private static final String TAG = "GolfMacro";
    private static final int MAX = 150_000;

    static volatile String last = "";

    static synchronized void i(Context c, String msg) {
        Log.i(TAG, msg);
        last = msg.trim();
        String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.KOREA).format(new Date())
                + "  " + msg + "\n";
        File f = new File(c.getFilesDir(), "log.txt");
        try {
            if (f.length() > MAX) {
                String all = read(c);
                String keep = all.substring(all.length() - MAX / 2);
                writeFile(f, keep, false);
            }
            writeFile(f, line, true);
        } catch (Exception e) {
            Log.w(TAG, "log write failed", e);
        }
    }

    static synchronized String read(Context c) {
        return readFile(new File(c.getFilesDir(), "log.txt"));
    }

    static synchronized void clear(Context c) {
        //noinspection ResultOfMethodCallIgnored
        new File(c.getFilesDir(), "log.txt").delete();
    }

    static String readFile(File f) {
        try {
            if (!f.exists()) return "";
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    static void writeFile(File f, String s, boolean append) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f, append)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
