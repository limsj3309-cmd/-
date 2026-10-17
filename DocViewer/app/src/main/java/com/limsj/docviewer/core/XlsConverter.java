package com.limsj.docviewer.core;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Legacy Excel 97-2003 (.xls, BIFF8) cell values. */
public final class XlsConverter {
    private final byte[] w;
    private final List<String> sst = new ArrayList<>();
    private final List<Integer> xfFmt = new ArrayList<>();
    private final Map<Integer, String> formats = new HashMap<>();
    private boolean date1904;
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    private XlsConverter(byte[] w) {
        this.w = w;
    }

    public static Doc convert(Cfb cfb) throws Exception {
        byte[] w = cfb.read("Workbook");
        if (w == null) {
            if (cfb.read("Book") != null) throw new Doc.UserError("Excel 5.0/95 문서는 지원하지 않습니다.");
            throw new Doc.UserError("Excel 통합 문서를 찾을 수 없습니다.");
        }
        return new Doc(new XlsConverter(w).run(), null, cfb);
    }

    private static final class SheetInfo {
        String name;
        long pos;
        boolean visible;
    }

    private String run() throws Exception {
        List<SheetInfo> infos = new ArrayList<>();
        int o = 0;
        boolean first = true;
        while (o + 4 <= w.length) {
            int type = Util.u16(w, o), len = Util.u16(w, o + 2);
            int d = o + 4;
            if (d + len > w.length) break;
            if (first) {
                first = false;
                if (type != 0x0809) throw new Doc.UserError("지원하지 않는 Excel 형식입니다.");
                if (Util.u16(w, d) != 0x0600) throw new Doc.UserError("Excel 95 이하 문서는 지원하지 않습니다.");
            }
            switch (type) {
                case 0x002F: throw new Doc.UserError("암호가 설정된 Excel 문서는 열 수 없습니다.");
                case 0x0022: date1904 = Util.u16(w, d) == 1; break;
                case 0x041E: {
                    int id = Util.u16(w, d);
                    int[] p = {d + 2};
                    formats.put(id, xlString(p, true, d + len));
                    break;
                }
                case 0x00E0: xfFmt.add(Util.u16(w, d + 2)); break;
                case 0x0085: {
                    SheetInfo si = new SheetInfo();
                    si.pos = Util.u32(w, d);
                    si.visible = (w[d + 4] & 3) == 0;
                    boolean worksheet = w[d + 5] == 0;
                    int[] p = {d + 6};
                    si.name = xlString(p, false, d + len);
                    if (worksheet) infos.add(si);
                    break;
                }
                case 0x00FC: o = readSst(o); continue;
                case 0x000A: o = w.length; continue; // end of globals
                default: break;
            }
            o = d + len;
        }
        List<Sheets.Sheet> sheets = new ArrayList<>();
        for (SheetInfo si : infos) {
            if (!si.visible) continue;
            Sheets.Sheet s = new Sheets.Sheet();
            s.name = si.name;
            try {
                readSheet((int) si.pos, s);
            } catch (RuntimeException ignored) {
            }
            sheets.add(s);
        }
        return Sheets.render(sheets);
    }

    /** XLUnicodeString (16-bit count) or ShortXLUnicodeString (8-bit count). */
    private String xlString(int[] p, boolean wide, int limit) {
        int o = p[0];
        int cch = wide ? Util.u16(w, o) : (w[o] & 0xFF);
        o += wide ? 2 : 1;
        int flags = w[o++] & 0xFF;
        boolean high = (flags & 1) != 0;
        int n = high ? cch * 2 : cch;
        if (o + n > Math.min(limit, w.length)) n = Math.max(0, Math.min(limit, w.length) - o);
        String s = high ? Util.utf16(w, o, n / 2) : new String(w, o, n, LATIN1);
        p[0] = o + n;
        return s;
    }

    /** Reads the SST record plus CONTINUE records. Returns the offset after them. */
    private int readSst(int o) {
        List<int[]> segs = new ArrayList<>(); // {start, end}
        int len = Util.u16(w, o + 2);
        segs.add(new int[]{o + 4, o + 4 + len});
        int next = o + 4 + len;
        while (next + 4 <= w.length && Util.u16(w, next) == 0x003C) {
            int l = Util.u16(w, next + 2);
            segs.add(new int[]{next + 4, next + 4 + l});
            next += 4 + l;
        }
        try {
            SegReader r = new SegReader(segs);
            r.skip(4);
            long unique = r.u32();
            for (long i = 0; i < unique && sst.size() < 2_000_000; i++) {
                if (r.eof()) break;
                sst.add(r.unicodeString());
            }
        } catch (RuntimeException ignored) {
        }
        return next;
    }

    private final class SegReader {
        final List<int[]> segs;
        int seg = 0, pos;

        SegReader(List<int[]> segs) {
            this.segs = segs;
            pos = segs.get(0)[0];
        }

        boolean eof() {
            return seg >= segs.size() || (seg == segs.size() - 1 && pos >= segs.get(seg)[1]);
        }

        void advance() {
            while (seg < segs.size() && pos >= segs.get(seg)[1]) {
                seg++;
                if (seg < segs.size()) pos = segs.get(seg)[0];
            }
            if (seg >= segs.size()) throw new IndexOutOfBoundsException();
        }

        int u8() {
            advance();
            return w[pos++] & 0xFF;
        }

        int u16() {
            return u8() | (u8() << 8);
        }

        long u32() {
            return (u16() & 0xFFFFL) | ((long) u16() << 16);
        }

        void skip(long n) {
            for (long i = 0; i < n; i++) u8();
        }

        String unicodeString() {
            int cch = u16();
            int flags = u8();
            boolean high = (flags & 1) != 0;
            int runs = (flags & 8) != 0 ? u16() : 0;
            long ext = (flags & 4) != 0 ? u32() : 0;
            StringBuilder sb = new StringBuilder(cch);
            for (int i = 0; i < cch; i++) {
                if (pos >= segs.get(seg)[1]) {
                    // character data continues in the next CONTINUE record with a fresh flags byte
                    seg++;
                    if (seg >= segs.size()) break;
                    pos = segs.get(seg)[0];
                    high = (w[pos++] & 1) != 0;
                }
                if (high) {
                    int lo = w[pos++] & 0xFF;
                    if (pos >= segs.get(seg)[1]) advance();
                    sb.append((char) (lo | ((w[pos++] & 0xFF) << 8)));
                } else sb.append((char) (w[pos++] & 0xFF));
            }
            skip(runs * 4L);
            skip(ext);
            return sb.toString();
        }
    }

    private String fmt(int xf) {
        if (xf < 0 || xf >= xfFmt.size()) return null;
        int id = xfFmt.get(xf);
        String f = formats.get(id);
        return f != null ? f : NumFmt.builtin(id);
    }

    private void num(Sheets.Sheet s, int r, int c, int xf, double v) {
        Sheets.Cell cell = new Sheets.Cell();
        cell.text = NumFmt.format(v, fmt(xf), date1904);
        cell.number = true;
        s.put(r, c, cell);
    }

    private void str(Sheets.Sheet s, int r, int c, String v) {
        Sheets.Cell cell = new Sheets.Cell();
        cell.text = v;
        s.put(r, c, cell);
    }

    private static double rk(int rk) {
        double v;
        if ((rk & 2) != 0) v = rk >> 2;
        else v = Double.longBitsToDouble(((long) (rk & 0xFFFFFFFC)) << 32);
        if ((rk & 1) != 0) v /= 100;
        return v;
    }

    private void readSheet(int o, Sheets.Sheet s) {
        int pendingR = -1, pendingC = -1;
        boolean firstRec = true;
        while (o + 4 <= w.length) {
            int type = Util.u16(w, o), len = Util.u16(w, o + 2);
            int d = o + 4;
            if (d + len > w.length) break;
            if (firstRec) {
                firstRec = false;
                if (type != 0x0809) return;
            }
            switch (type) {
                case 0x00FD: { // LABELSST
                    int r = Util.u16(w, d), c = Util.u16(w, d + 2);
                    long i = Util.u32(w, d + 6);
                    str(s, r, c, i < sst.size() ? sst.get((int) i) : "");
                    break;
                }
                case 0x0203: { // NUMBER
                    int r = Util.u16(w, d), c = Util.u16(w, d + 2), xf = Util.u16(w, d + 4);
                    num(s, r, c, xf, Double.longBitsToDouble(Util.i64(w, d + 6)));
                    break;
                }
                case 0x027E: { // RK
                    int r = Util.u16(w, d), c = Util.u16(w, d + 2), xf = Util.u16(w, d + 4);
                    num(s, r, c, xf, rk(Util.i32(w, d + 6)));
                    break;
                }
                case 0x00BD: { // MULRK
                    int r = Util.u16(w, d), c = Util.u16(w, d + 2);
                    int n = (len - 6) / 6;
                    for (int i = 0; i < n; i++) {
                        int xf = Util.u16(w, d + 4 + i * 6);
                        num(s, r, c + i, xf, rk(Util.i32(w, d + 6 + i * 6)));
                    }
                    break;
                }
                case 0x0204: { // LABEL
                    int r = Util.u16(w, d), c = Util.u16(w, d + 2);
                    int[] p = {d + 6};
                    str(s, r, c, xlString(p, true, d + len));
                    break;
                }
                case 0x0006: { // FORMULA
                    int r = Util.u16(w, d), c = Util.u16(w, d + 2), xf = Util.u16(w, d + 4);
                    if (Util.u16(w, d + 12) == 0xFFFF) {
                        int t = w[d + 6] & 0xFF;
                        if (t == 0) {
                            pendingR = r;
                            pendingC = c;
                        } else if (t == 1) str(s, r, c, w[d + 8] != 0 ? "TRUE" : "FALSE");
                        else if (t == 2) str(s, r, c, "#ERROR");
                    } else num(s, r, c, xf, Double.longBitsToDouble(Util.i64(w, d + 6)));
                    break;
                }
                case 0x0207: { // STRING (formula result)
                    if (pendingR >= 0) {
                        int[] p = {d};
                        str(s, pendingR, pendingC, xlString(p, true, d + len));
                        pendingR = -1;
                    }
                    break;
                }
                case 0x0205: { // BOOLERR
                    int r = Util.u16(w, d), c = Util.u16(w, d + 2);
                    boolean err = w[d + 7] != 0;
                    str(s, r, c, err ? "#ERROR" : (w[d + 6] != 0 ? "TRUE" : "FALSE"));
                    break;
                }
                case 0x00E5: { // MERGEDCELLS
                    int n = Util.u16(w, d);
                    for (int i = 0; i < n && d + 2 + i * 8 + 8 <= d + len; i++) {
                        int b = d + 2 + i * 8;
                        s.merges.add(new int[]{Util.u16(w, b), Util.u16(w, b + 4), Util.u16(w, b + 2), Util.u16(w, b + 6)});
                    }
                    break;
                }
                case 0x007D: { // COLINFO
                    int c1 = Util.u16(w, d), c2 = Util.u16(w, d + 2), wd = Util.u16(w, d + 4);
                    boolean hidden = (Util.u16(w, d + 8) & 1) != 0;
                    int px = hidden ? 0 : Math.min(600, (int) Math.round(wd / 256.0 * 7 + 5));
                    for (int c = c1; c <= Math.min(c2, Sheets.MAX_COLS); c++) s.colWidthPx.put(c, px);
                    break;
                }
                case 0x000A: return; // EOF
                default: break;
            }
            o = d + len;
        }
    }
}
