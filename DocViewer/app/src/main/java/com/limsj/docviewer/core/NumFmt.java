package com.limsj.docviewer.core;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

/** Small subset of Excel number formatting, good enough for viewing. */
public final class NumFmt {
    private NumFmt() {}

    public static String builtin(int id) {
        switch (id) {
            case 0: return "General";
            case 1: return "0";
            case 2: return "0.00";
            case 3: return "#,##0";
            case 4: return "#,##0.00";
            case 5: case 6: return "#,##0";
            case 7: case 8: return "#,##0.00";
            case 9: return "0%";
            case 10: return "0.00%";
            case 11: return "0.00E+00";
            case 12: case 13: return "General";
            case 14: return "yyyy-mm-dd";
            case 15: case 16: case 17: return "yyyy-mm-dd";
            case 18: case 19: case 20: case 21: return "h:mm:ss";
            case 22: return "yyyy-mm-dd h:mm";
            case 37: case 38: return "#,##0";
            case 39: case 40: return "#,##0.00";
            case 41: case 42: return "#,##0";
            case 43: case 44: return "#,##0.00";
            case 45: case 46: case 47: return "mm:ss";
            case 48: return "0.0E+0";
            case 49: return "@";
            // Korean locale built-ins
            case 27: case 28: case 29: case 30: case 31: case 34: case 35: case 36:
            case 50: case 51: case 52: case 53: case 54: case 55: case 56: case 57: case 58:
                return "yyyy-mm-dd";
            case 32: case 33: return "h:mm:ss";
            default: return "General";
        }
    }

    /** Strips quoted text, escapes and [..] blocks to look at the format's tokens. */
    private static String tokens(String f) {
        StringBuilder sb = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < f.length(); i++) {
            char c = f.charAt(i);
            if (c == '"') { q = !q; continue; }
            if (q) continue;
            if (c == '\\' || c == '_' || c == '*') { i++; continue; }
            if (c == '[') {
                int e = f.indexOf(']', i);
                if (e < 0) break;
                String in = f.substring(i + 1, e).toLowerCase(Locale.ROOT);
                if (in.equals("h") || in.equals("hh") || in.equals("m") || in.equals("mm") || in.equals("s") || in.equals("ss")) sb.append(in);
                i = e;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public static boolean isDate(String fmt) {
        if (fmt == null) return false;
        String sec = fmt.split(";", -1)[0];
        String t = tokens(sec).toLowerCase(Locale.ROOT);
        if (t.contains("general")) return false;
        boolean any = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == 'y' || c == 'd' || c == 'h' || c == 's' || c == 'm') any = true;
        }
        if (!any) return false;
        if (t.contains("0") || t.contains("#")) return t.contains("y") || t.contains("d") || t.contains("h") || t.contains(":");
        return true;
    }

    public static String format(double v, String fmt, boolean date1904) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "#NUM!";
        if (fmt == null || fmt.isEmpty() || fmt.equalsIgnoreCase("General") || fmt.equals("@")) return general(v);
        try {
            if (isDate(fmt)) return date(v, fmt, date1904);
            String[] secs = fmt.split(";", -1);
            String sec = secs[0];
            boolean neg = v < 0;
            if (neg && secs.length > 1 && !secs[1].isEmpty()) {
                sec = secs[1];
                v = -v;
                neg = false;
            } else if (v == 0 && secs.length > 2 && !secs[2].isEmpty()) {
                sec = secs[2];
            }
            String t = tokens(sec);
            if (t.trim().equalsIgnoreCase("general")) return (neg ? "-" : "") + general(Math.abs(v));
            int firstNum = -1, lastNum = -1;
            for (int i = 0; i < sec.length(); i++) {
                char c = sec.charAt(i);
                if (c == '0' || c == '#' || c == '?' ) {
                    if (firstNum < 0) firstNum = i;
                    lastNum = i;
                }
            }
            if (firstNum < 0) return literal(sec) .isEmpty() ? general(v) : literal(sec);
            String core = sec.substring(firstNum, lastNum + 1);
            String prefix = literal(sec.substring(0, firstNum));
            String suffix = literal(sec.substring(lastNum + 1));
            boolean pct = t.contains("%");
            boolean sci = t.toUpperCase(Locale.ROOT).contains("E+") || t.toUpperCase(Locale.ROOT).contains("E-");
            double x = Math.abs(v) * (pct ? 100 : 1);
            int dot = core.indexOf('.');
            int dec = 0;
            if (dot >= 0) for (int i = dot + 1; i < core.length(); i++) {
                char c = core.charAt(i);
                if (c == '0' || c == '#' || c == '?') dec++;
                else if (c == 'E' || c == 'e') break;
            }
            String body;
            if (sci) {
                body = String.format(Locale.ROOT, "%." + dec + "E", x).replace("E", "E+").replace("E+-", "E-");
            } else {
                boolean group = core.contains(",") && (dot < 0 || core.indexOf(',') < dot);
                int trailingCommas = 0;
                String intPart = dot >= 0 ? core.substring(0, dot) : core;
                for (int i = intPart.length() - 1; i >= 0 && intPart.charAt(i) == ','; i--) trailingCommas++;
                for (int i = 0; i < trailingCommas; i++) x /= 1000;
                StringBuilder pat = new StringBuilder(group ? "#,##0" : "0");
                int minDec = 0;
                if (dot >= 0) for (int i = dot + 1; i < core.length(); i++) if (core.charAt(i) == '0') minDec++;
                if (dec > 0) {
                    pat.append('.');
                    for (int i = 0; i < dec; i++) pat.append(i < minDec ? '0' : '#');
                }
                DecimalFormat df = new DecimalFormat(pat.toString(), DecimalFormatSymbols.getInstance(Locale.US));
                df.setRoundingMode(java.math.RoundingMode.HALF_UP);
                body = df.format(new BigDecimal(Double.toString(x)));
                if (!core.contains("0") && body.equals("0")) body = "";
            }
            if (pct && !suffix.contains("%")) suffix = "%" + suffix;
            boolean showNeg = neg && Math.abs(v) * (pct ? 100 : 1) >= Math.pow(10, -dec) / 2;
            return (showNeg ? "-" : "") + prefix + body + suffix;
        } catch (Exception e) {
            return general(v);
        }
    }

    private static String literal(String s) {
        StringBuilder sb = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') { q = !q; continue; }
            if (q) { sb.append(c); continue; }
            if (c == '\\' && i + 1 < s.length()) { sb.append(s.charAt(++i)); continue; }
            if (c == '_' || c == '*') { i++; if (c == '_') sb.append(' '); continue; }
            if (c == '[') {
                int e = s.indexOf(']', i);
                if (e < 0) break;
                String in = s.substring(i + 1, e);
                if (in.startsWith("$")) {
                    int dash = in.indexOf('-');
                    sb.append(dash > 0 ? in.substring(1, dash) : in.substring(1));
                }
                i = e;
                continue;
            }
            if (c == '%' || c == ',' || c == '.') { if (c == '%') sb.append(c); continue; }
            if (c == '0' || c == '#' || c == '?') continue;
            sb.append(c);
        }
        return sb.toString();
    }

    public static String general(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        double a = Math.abs(v);
        if (a != 0 && (a >= 1e11 || a < 1e-9)) {
            return String.format(Locale.ROOT, "%.5E", v).replaceAll("\\.?0+E", "E").replace("E", "E+").replace("E+-", "E-");
        }
        BigDecimal bd = new BigDecimal(v).round(new java.math.MathContext(11));
        String s = bd.stripTrailingZeros().toPlainString();
        return s;
    }

    private static String date(double v, String fmt, boolean date1904) {
        if (v < 0 || v > 2958466) return general(v);
        long days = (long) Math.floor(v);
        double frac = v - days;
        long secs = Math.round(frac * 86400);
        if (secs >= 86400) { days++; secs -= 86400; }
        Calendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        c.clear();
        if (date1904) {
            c.set(1904, Calendar.JANUARY, 1);
            c.add(Calendar.DAY_OF_MONTH, (int) days);
        } else {
            c.set(1899, Calendar.DECEMBER, 31);
            if (days >= 60) days--; // Excel's fictional 1900-02-29
            c.add(Calendar.DAY_OF_MONTH, (int) days);
        }
        String t = tokens(fmt.split(";", -1)[0]).toLowerCase(Locale.ROOT);
        boolean hasDate = t.contains("y") || t.contains("d") || (t.contains("m") && !t.contains("h") && !t.contains("s"));
        boolean hasTime = t.contains("h") || t.contains("s");
        boolean hasSec = t.contains("s");
        StringBuilder sb = new StringBuilder();
        if (hasDate) {
            boolean y = t.contains("y") || t.contains("e") || !t.contains("d");
            boolean d = t.contains("d");
            if (y) sb.append(c.get(Calendar.YEAR));
            if (y) sb.append('-');
            sb.append(String.format(Locale.ROOT, "%02d", c.get(Calendar.MONTH) + 1));
            if (d) sb.append('-').append(String.format(Locale.ROOT, "%02d", c.get(Calendar.DAY_OF_MONTH)));
        }
        if (hasTime) {
            if (sb.length() > 0) sb.append(' ');
            long h = secs / 3600, m = (secs / 60) % 60, s = secs % 60;
            if (t.contains("[h")) h += days * 24;
            if (t.contains("h")) sb.append(h).append(':').append(String.format(Locale.ROOT, "%02d", m));
            else sb.append(String.format(Locale.ROOT, "%02d", m));
            if (hasSec) sb.append(':').append(String.format(Locale.ROOT, "%02d", s));
        }
        return sb.length() == 0 ? general(v) : sb.toString();
    }
}
