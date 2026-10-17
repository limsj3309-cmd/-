package com.limsj.docviewer.core;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Legacy PowerPoint 97-2003 (.ppt): slide text in presentation order. */
public final class PptConverter {
    private static final int SLIDE = 0x03EE, DOCUMENT = 0x03E8, SLWT = 0x0FF0, SLIDE_PERSIST = 0x03F3;
    private static final int TEXT_HEADER = 0x0F9F, TEXT_CHARS = 0x0FA0, TEXT_BYTES = 0x0FA8, USER_EDIT = 0x0FF5, PERSIST_DIR = 0x1772;
    private static final Charset CP1252 = Charset.forName("windows-1252");

    private final byte[] d;

    private PptConverter(byte[] d) {
        this.d = d;
    }

    private static final class Para {
        String text;
        boolean title;
    }

    public static Doc convert(Cfb cfb) throws Exception {
        byte[] d = cfb.read("PowerPoint Document");
        if (d == null) throw new Doc.UserError("PowerPoint 문서를 찾을 수 없습니다.");
        if (cfb.has("EncryptedSummary")) throw new Doc.UserError("암호가 설정된 PowerPoint 문서는 열 수 없습니다.");
        PptConverter c = new PptConverter(d);
        List<List<Para>> slides = null;
        try {
            slides = c.ordered(cfb.read("Current User"));
        } catch (RuntimeException ignored) {
        }
        if (slides == null || slides.isEmpty() || allEmpty(slides)) slides = c.linear();
        StringBuilder sb = new StringBuilder();
        int n = slides.size();
        for (int i = 0; i < n; i++) {
            sb.append("<div class=\"card\"><div class=\"num\">").append(i + 1).append(" / ").append(n).append("</div>");
            for (Para p : slides.get(i)) {
                String e = Util.esc(p.text).replace("\n", "<br>").replace("\t", "&emsp;");
                sb.append(p.title ? "<h3>" : "<p>").append(e.isEmpty() ? "&nbsp;" : e).append(p.title ? "</h3>" : "</p>");
            }
            sb.append("</div>");
        }
        if (n == 0) return new Doc(Html.message("빈 프레젠테이션", "표시할 슬라이드가 없습니다."), null, cfb);
        String css = "body{background:#3a3b3f}"
                + ".card{background:#fff;max-width:900px;margin:12px auto;padding:18px 20px 14px;aspect-ratio:4/3;overflow:auto;box-shadow:0 2px 8px rgba(0,0,0,.5);font-family:sans-serif}"
                + ".card h3{margin:0 0 12px;font-size:22px;color:#1f3864}"
                + ".card p{margin:4px 0;font-size:16px;line-height:1.45}"
                + ".num{float:right;color:#999;font-size:12px}";
        String note = "<div class=\"note\">구형 PPT 파일은 슬라이드의 글자 위주로 표시됩니다.</div>";
        return new Doc(Html.page("ppt", css, note + sb, null), null, cfb);
    }

    private static boolean allEmpty(List<List<Para>> s) {
        for (List<Para> l : s) if (!l.isEmpty()) return false;
        return true;
    }

    /** Uses CurrentUser -> UserEditAtom -> PersistDirectory to find slides in presentation order. */
    private List<List<Para>> ordered(byte[] cu) {
        if (cu == null || cu.length < 20) return null;
        long editOff = Util.u32(cu, 16);
        Map<Integer, Long> persist = new HashMap<>();
        List<Long> edits = new ArrayList<>();
        long docRef = -1;
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        while (editOff > 0 && editOff + 8 < d.length && seen.add(editOff)) {
            if (Util.u16(d, (int) editOff + 2) != USER_EDIT) break;
            edits.add(editOff);
            int b = (int) editOff + 8;
            if (docRef < 0) docRef = Util.u32(d, b + 16);
            editOff = Util.u32(d, b + 8);
        }
        for (int i = edits.size() - 1; i >= 0; i--) { // oldest first, newer overrides
            int b = edits.get(i).intValue() + 8;
            long pd = Util.u32(d, b + 12);
            if (pd + 8 > d.length || Util.u16(d, (int) pd + 2) != PERSIST_DIR) continue;
            int len = Util.i32(d, (int) pd + 4);
            int o = (int) pd + 8, end = Math.min(d.length, o + len);
            while (o + 4 <= end) {
                long v = Util.u32(d, o);
                int id = (int) (v & 0xFFFFF);
                int cnt = (int) (v >>> 20);
                o += 4;
                for (int k = 0; k < cnt && o + 4 <= end; k++, o += 4) persist.put(id + k, Util.u32(d, o));
            }
        }
        Long docOff = persist.get((int) docRef);
        if (docOff == null || docOff + 8 > d.length || Util.u16(d, docOff.intValue() + 2) != DOCUMENT) return null;
        // find SlideListWithText (instance 0) in the document container
        int dOff = docOff.intValue();
        int dEnd = (int) Math.min(d.length, dOff + 8 + Util.u32(d, dOff + 4));
        List<List<Para>> out = new ArrayList<>();
        int o = dOff + 8;
        while (o + 8 <= dEnd) {
            int verInst = Util.u16(d, o), type = Util.u16(d, o + 2);
            long len = Util.u32(d, o + 4);
            if (type == SLWT && (verInst >> 4) == 0) {
                int so = o + 8, se = (int) Math.min(dEnd, o + 8 + len);
                List<Para> legacy = null;
                Long slideOff = null;
                int textType = -1;
                while (so + 8 <= se) {
                    int t = Util.u16(d, so + 2);
                    long l = Util.u32(d, so + 4);
                    if (t == SLIDE_PERSIST) {
                        if (legacy != null) out.add(pick(slideOff, legacy));
                        legacy = new ArrayList<>();
                        slideOff = persist.get((int) Util.u32(d, so + 8));
                        textType = -1;
                    } else if (t == TEXT_HEADER) {
                        textType = Util.i32(d, so + 8);
                    } else if ((t == TEXT_CHARS || t == TEXT_BYTES) && legacy != null) {
                        addText(legacy, so, t, l, textType);
                    }
                    so += 8 + (int) Math.max(0, Math.min(l, Integer.MAX_VALUE - so));
                }
                if (legacy != null) out.add(pick(slideOff, legacy));
                break;
            }
            o += 8 + (int) Math.max(0, Math.min(len, Integer.MAX_VALUE - o));
        }
        return out;
    }

    private List<Para> pick(Long slideOff, List<Para> legacy) {
        if (slideOff != null && slideOff + 8 <= d.length && Util.u16(d, slideOff.intValue() + 2) == SLIDE) {
            List<Para> inSlide = new ArrayList<>();
            int s = slideOff.intValue();
            collect(s + 8, (int) Math.min(d.length, s + 8 + Util.u32(d, s + 4)), inSlide, new int[]{-1}, 0);
            if (!inSlide.isEmpty()) return inSlide;
        }
        return legacy;
    }

    private void collect(int o, int end, List<Para> out, int[] textType, int depth) {
        if (depth > 32) return;
        while (o + 8 <= end) {
            int verInst = Util.u16(d, o), type = Util.u16(d, o + 2);
            long len = Util.u32(d, o + 4);
            int recEnd = (int) Math.min(end, o + 8 + len);
            if ((verInst & 0xF) == 0xF) collect(o + 8, recEnd, out, textType, depth + 1);
            else if (type == TEXT_HEADER) textType[0] = Util.i32(d, o + 8);
            else if (type == TEXT_CHARS || type == TEXT_BYTES) addText(out, o, type, len, textType[0]);
            if (len < 0 || o + 8 + len > Integer.MAX_VALUE) break;
            o = (int) (o + 8 + len);
        }
    }

    private void addText(List<Para> out, int o, int type, long len, int textType) {
        int start = o + 8;
        int n = (int) Math.min(len, d.length - start);
        if (n <= 0) return;
        String s = type == TEXT_CHARS ? Util.utf16(d, start, n / 2) : new String(d, start, n, CP1252);
        boolean title = textType == 0 || textType == 6;
        for (String line : s.split("\r")) {
            Para p = new Para();
            p.text = line.replace('\u000B', '\n');
            p.title = title;
            if (!p.text.trim().isEmpty()) out.add(p);
        }
    }

    /** Fallback: stream order, one card per Slide container. */
    private List<List<Para>> linear() {
        List<List<Para>> out = new ArrayList<>();
        int o = 0;
        while (o + 8 <= d.length) {
            int type = Util.u16(d, o + 2);
            long len = Util.u32(d, o + 4);
            if (type == SLIDE) {
                List<Para> s = new ArrayList<>();
                collect(o + 8, (int) Math.min(d.length, o + 8 + len), s, new int[]{-1}, 0);
                out.add(s);
            }
            if (len < 0 || o + 8 + len > d.length) break;
            o = (int) (o + 8 + len);
        }
        return out;
    }
}
