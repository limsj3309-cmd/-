package com.limsj.docviewer.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.Inflater;

public final class Util {
    private Util() {}

    public static int parseInt(String v, int def) {
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            try {
                return (int) Double.parseDouble(v.trim());
            } catch (Exception e2) {
                return def;
            }
        }
    }

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String r = null;
            switch (c) {
                case '<': r = "&lt;"; break;
                case '>': r = "&gt;"; break;
                case '&': r = "&amp;"; break;
                case '"': r = "&quot;"; break;
                case '\'': r = "&#39;"; break;
                default:
                    if (c < 0x20 && c != '\n' && c != '\t') r = "";
            }
            if (r != null) {
                if (sb == null) {
                    sb = new StringBuilder(s.length() + 16);
                    sb.append(s, 0, i);
                }
                sb.append(r);
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? s : sb.toString();
    }

    public static void escTo(StringBuilder sb, String s) {
        sb.append(esc(s));
    }

    /** Resolves an OPC relationship target against the directory of the source part. */
    public static String resolve(String baseDir, String target) {
        if (target == null) return null;
        target = target.replace('\\', '/');
        String path;
        if (target.startsWith("/")) path = target.substring(1);
        else path = (baseDir == null || baseDir.isEmpty() ? "" : baseDir + "/") + target;
        String[] parts = path.split("/");
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
            } else out.add(p);
        }
        StringBuilder sb = new StringBuilder();
        for (String p : out) {
            if (sb.length() > 0) sb.append('/');
            sb.append(p);
        }
        try {
            return java.net.URLDecoder.decode(sb.toString().replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return sb.toString();
        }
    }

    public static String dirOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    public static String ext(String name) {
        if (name == null) return "";
        int q = name.indexOf('?');
        if (q >= 0) name = name.substring(0, q);
        int i = name.lastIndexOf('.');
        int s = name.lastIndexOf('/');
        if (i < 0 || i < s) return "";
        return name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    /** Image types a WebView can display; null when not displayable (EMF/WMF/...). */
    public static String imageMime(String nameOrExt) {
        String e = nameOrExt.contains(".") ? ext(nameOrExt) : nameOrExt.toLowerCase(Locale.ROOT);
        switch (e) {
            case "png": return "image/png";
            case "jpg": case "jpeg": case "jpe": case "jfif": return "image/jpeg";
            case "gif": return "image/gif";
            case "bmp": case "dib": return "image/bmp";
            case "webp": return "image/webp";
            case "svg": return "image/svg+xml";
            case "ico": return "image/x-icon";
            default: return null;
        }
    }

    /** Sniffs image bytes; returns mime or null. */
    public static String sniffImage(byte[] b) {
        if (b == null || b.length < 4) return null;
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "image/gif";
        if (b[0] == 'B' && b[1] == 'M') return "image/bmp";
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[8] == 'W' && b[9] == 'E') return "image/webp";
        return null;
    }

    public static byte[] readAll(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        long total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > limit) throw new IOException("too large");
            bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }

    /** Raw-deflate (no zlib header) decompression as used by HWP. */
    public static byte[] inflateRaw(byte[] data, long limit) throws Exception {
        Inflater inf = new Inflater(true);
        try {
            inf.setInput(data);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.max(1024, data.length * 3));
            byte[] buf = new byte[65536];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                }
                bo.write(buf, 0, n);
                if (bo.size() > limit) throw new IOException("too large");
            }
            return bo.toByteArray();
        } finally {
            inf.end();
        }
    }

    public static int u16(byte[] b, int o) {
        if (o < 0 || o + 2 > b.length) return 0;
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    public static int i32(byte[] b, int o) {
        if (o < 0 || o + 4 > b.length) return 0;
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    public static long u32(byte[] b, int o) {
        return i32(b, o) & 0xFFFFFFFFL;
    }

    public static long i64(byte[] b, int o) {
        return (u32(b, o)) | ((long) i32(b, o + 4) << 32);
    }

    public static String utf16(byte[] b, int o, int chars) {
        if (o < 0) return "";
        int n = Math.min(chars, (b.length - o) / 2);
        if (n <= 0) return "";
        char[] c = new char[n];
        for (int i = 0; i < n; i++) c[i] = (char) u16(b, o + i * 2);
        return new String(c);
    }

    /** "#RRGGBB" from a 6/8 hex string, or null. */
    public static String hexColor(String v) {
        if (v == null) return null;
        v = v.trim();
        if (v.startsWith("#")) v = v.substring(1);
        if (v.length() == 8) v = v.substring(2);
        if (v.length() != 6) return null;
        for (int i = 0; i < 6; i++) if (Character.digit(v.charAt(i), 16) < 0) return null;
        return "#" + v.toUpperCase(Locale.ROOT);
    }

    /** COLORREF (0x00BBGGRR) to CSS. */
    public static String colorRef(int c) {
        int r = c & 0xFF, g = (c >> 8) & 0xFF, b = (c >> 16) & 0xFF;
        return String.format(Locale.ROOT, "#%02X%02X%02X", r, g, b);
    }

    public static String fmt(double d) {
        if (d == Math.rint(d)) return Long.toString((long) d);
        return String.format(Locale.ROOT, "%.2f", d);
    }

    public static String urlPath(String p) {
        StringBuilder sb = new StringBuilder();
        for (byte b : p.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '/' || c == '.' || c == '-' || c == '_') sb.append((char) c);
            else sb.append('%').append(String.format(Locale.ROOT, "%02X", c));
        }
        return sb.toString();
    }
}
