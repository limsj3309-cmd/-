package kr.golfmacro.app;

import android.content.Context;
import android.content.SharedPreferences;

/** 사용자 설정 저장소. */
final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    boolean enabled() { return sp.getBoolean("enabled", false); }
    String targetTime() { return sp.getString("target", "20:00"); }
    String openTime() { return sp.getString("open", "06:00:00"); }
    int leadSec() { return sp.getInt("lead", 60); }
    int clickDelayMs() { return sp.getInt("delay", 150); }
    String testTime() { return sp.getString("test", "21:00"); }
    long nextRun() { return sp.getLong("next", 0); }
    int maxTries() { return sp.getInt("maxTries", 5); }
    boolean closeAfter() { return sp.getBoolean("closeAfter", true); }

    void saveRunLimits(int maxTries, boolean closeAfter) {
        sp.edit().putInt("maxTries", maxTries).putBoolean("closeAfter", closeAfter).apply();
    }

    String priority() { return sp.getString("priority", "6,5,4,3"); }
    boolean allowOthers() { return sp.getBoolean("others", true); }

    void savePriority(String priority, boolean others) {
        sp.edit().putString("priority", priority).putBoolean("others", others).apply();
    }

    /** "6,5 4/3" → [6,5,4,3] */
    static java.util.List<Integer> parsePriority(String s) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        if (s == null) return out;
        for (String t : s.split("[^0-9]+")) {
            if (t.isEmpty()) continue;
            try {
                int v = Integer.parseInt(t);
                if (!out.contains(v)) out.add(v);
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    void save(boolean enabled, String target, String open, int lead, int delay, String test) {
        sp.edit()
                .putBoolean("enabled", enabled)
                .putString("target", target)
                .putString("open", open)
                .putInt("lead", lead)
                .putInt("delay", delay)
                .putString("test", test)
                .apply();
    }

    void setEnabled(boolean on) { sp.edit().putBoolean("enabled", on).apply(); }

    void setNextRun(long t) { sp.edit().putLong("next", t).apply(); }

    /** "20", "2000", "20:0", "20:00" → "20:00". 잘못되면 null. */
    static String normHm(String s) {
        if (s == null) return null;
        s = s.trim().replace(" ", "");
        int h, m;
        try {
            if (s.contains(":")) {
                String[] p = s.split(":");
                h = Integer.parseInt(p[0]);
                m = p.length > 1 && !p[1].isEmpty() ? Integer.parseInt(p[1]) : 0;
            } else if (s.length() <= 2) {
                h = Integer.parseInt(s);
                m = 0;
            } else {
                int v = Integer.parseInt(s);
                h = v / 100;
                m = v % 100;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        if (h < 0 || h > 23 || m < 0 || m > 59) return null;
        return String.format(java.util.Locale.US, "%02d:%02d", h, m);
    }

    /** "6", "06:00", "06:00:00" → {h, m, s}. 잘못되면 null. */
    static int[] parseHms(String s) {
        if (s == null) return null;
        String[] p = s.trim().split(":");
        try {
            int h = Integer.parseInt(p[0].trim());
            int m = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
            int sec = p.length > 2 ? Integer.parseInt(p[2].trim()) : 0;
            if (h < 0 || h > 23 || m < 0 || m > 59 || sec < 0 || sec > 59) return null;
            return new int[]{h, m, sec};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
