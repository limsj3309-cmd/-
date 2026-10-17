package com.limsj.docviewer.core;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** Legacy Word 97-2003 (.doc): text, paragraphs and tables via the piece table. */
public final class DocConverter {
    private DocConverter() {}

    private static final class Piece {
        long cpStart, cpEnd, fc;
        boolean compressed;
    }

    public static Doc convert(Cfb cfb) throws Exception {
        byte[] wd = cfb.read("WordDocument");
        if (wd == null || wd.length < 0x200) throw new Doc.UserError("Word 문서 본문을 찾을 수 없습니다.");
        int ident = Util.u16(wd, 0);
        if (ident != 0xA5EC) throw new Doc.UserError("지원하지 않는 Word 문서 형식입니다.");
        int nFib = Util.u16(wd, 2);
        if (nFib < 101) throw new Doc.UserError("Word 6.0/95 문서는 지원하지 않습니다.");
        int flags = Util.u16(wd, 0x0A);
        if ((flags & 0x0100) != 0) throw new Doc.UserError("암호가 설정된 Word 문서는 열 수 없습니다.");
        byte[] table = cfb.read((flags & 0x0200) != 0 ? "1Table" : "0Table");
        if (table == null) throw new Doc.UserError("Word 문서 구조를 읽을 수 없습니다.");

        int o = 32;
        int csw = Util.u16(wd, o);
        o += 2 + csw * 2;
        int cslw = Util.u16(wd, o);
        int lwStart = o + 2;
        long ccpText = Util.u32(wd, lwStart + 3 * 4);
        o = lwStart + cslw * 4;
        int fcStart = o + 2;
        long fcClx = Util.u32(wd, fcStart + 33 * 8);
        long lcbClx = Util.u32(wd, fcStart + 33 * 8 + 4);
        long fcPapx = Util.u32(wd, fcStart + 13 * 8);
        long lcbPapx = Util.u32(wd, fcStart + 13 * 8 + 4);

        List<Piece> pieces = pieces(table, fcClx, lcbClx);
        char[] cp1252 = new char[256];
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) all[i] = (byte) i;
        new String(all, Charset.forName("windows-1252")).getChars(0, 256, cp1252, 0);
        StringBuilder text = new StringBuilder();
        List<Long> cpFc = new ArrayList<>(); // fc of each char (for paragraph properties lookup)
        for (Piece p : pieces) {
            if (p.cpStart >= ccpText) break;
            long end = Math.min(p.cpEnd, ccpText);
            long n = end - p.cpStart;
            for (long k = 0; k < n; k++) {
                long fc = p.compressed ? p.fc + k : p.fc + k * 2;
                if (fc < 0 || fc + (p.compressed ? 1 : 2) > wd.length) break;
                char ch;
                if (p.compressed) ch = cp1252[wd[(int) fc] & 0xFF];
                else ch = (char) Util.u16(wd, (int) fc);
                text.append(ch);
                cpFc.add(fc);
            }
            if (text.length() > 20_000_000) break;
        }
        Ttp ttp = new Ttp(wd, table, fcPapx, lcbPapx);
        String html = render(text, cpFc, ttp);
        return new Doc(Html.page("doc", null, html, null), null, cfb);
    }

    private static List<Piece> pieces(byte[] t, long fcClx, long lcbClx) {
        List<Piece> out = new ArrayList<>();
        int o = (int) fcClx;
        int end = (int) Math.min(t.length, fcClx + lcbClx);
        while (o < end) {
            int type = t[o] & 0xFF;
            if (type == 1) {
                int cb = Util.u16(t, o + 1);
                o += 3 + cb;
            } else if (type == 2) {
                long lcb = Util.u32(t, o + 1);
                int base = o + 5;
                int n = (int) ((lcb - 4) / 12);
                if (n <= 0 || base + lcb > t.length) break;
                for (int i = 0; i < n; i++) {
                    Piece p = new Piece();
                    p.cpStart = Util.u32(t, base + i * 4);
                    p.cpEnd = Util.u32(t, base + (i + 1) * 4);
                    int pcd = base + (n + 1) * 4 + i * 8;
                    long fc = Util.u32(t, pcd + 2);
                    p.compressed = (fc & 0x40000000L) != 0;
                    p.fc = p.compressed ? (fc & ~0x40000000L) / 2 : fc;
                    out.add(p);
                }
                break;
            } else break;
        }
        return out;
    }

    /** Paragraph table flags (sprmPFInTable / sprmPFTtp) from the paragraph FKPs. */
    private static final class Ttp {
        final List<long[]> ranges = new ArrayList<>(); // {fcStart, fcEnd, flags} 1=inTable 2=rowEnd

        Ttp(byte[] wd, byte[] table, long fc, long lcb) {
            try {
                int n = (int) ((lcb - 4) / 8);
                for (int i = 0; i < n; i++) {
                    long pn = Util.u32(table, (int) (fc + (n + 1) * 4 + i * 4)) & 0x3FFFFF;
                    int page = (int) (pn * 512);
                    if (page + 512 > wd.length) continue;
                    int crun = wd[page + 511] & 0xFF;
                    for (int r = 0; r < crun; r++) {
                        long a = Util.u32(wd, page + r * 4), b = Util.u32(wd, page + (r + 1) * 4);
                        int bx = wd[page + (crun + 1) * 4 + r * 13] & 0xFF;
                        if (bx == 0) continue;
                        int px = page + bx * 2;
                        int cb = wd[px] & 0xFF;
                        int len, start;
                        if (cb == 0) {
                            len = (wd[px + 1] & 0xFF) * 2;
                            start = px + 2;
                        } else {
                            len = cb * 2 - 1;
                            start = px + 1;
                        }
                        int f = flags(wd, start + 2, start + len);
                        if (f != 0) ranges.add(new long[]{a, b, f});
                    }
                }
            } catch (RuntimeException ignored) {
            }
            ranges.sort((x, y) -> Long.compare(x[0], y[0]));
        }

        private static int flags(byte[] b, int o, int end) {
            end = Math.min(end, b.length);
            int f = 0;
            while (o + 2 <= end) {
                int sprm = Util.u16(b, o);
                o += 2;
                if (o < end && b[o] != 0) {
                    if (sprm == 0x2416 || sprm == 0x244B) f |= 1;
                    if (sprm == 0x2417 || sprm == 0x244C) f |= 2;
                }
                int spra = sprm >> 13;
                int size;
                switch (spra) {
                    case 0: case 1: size = 1; break;
                    case 2: case 4: case 5: size = 2; break;
                    case 3: size = 4; break;
                    case 7: size = 3; break;
                    default:
                        if (sprm == 0xD608 || sprm == 0xD606) return f;
                        size = (o < end ? (b[o] & 0xFF) : 0) + 1;
                        break;
                }
                o += size;
            }
            return f;
        }

        int flags(long fc) {
            int lo = 0, hi = ranges.size() - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                long[] r = ranges.get(mid);
                if (fc < r[0]) hi = mid - 1;
                else if (fc >= r[1]) lo = mid + 1;
                else return (int) r[2];
            }
            return 0;
        }
    }

    private static String render(StringBuilder t, List<Long> cpFc, Ttp ttp) {
        StringBuilder sb = new StringBuilder(t.length() + 4096);
        sb.append("<div class=\"page\">");
        StringBuilder para = new StringBuilder();
        List<String> cellBuf = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        List<Boolean> showing = new ArrayList<>(); // per open field: past the separator?
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == 0x13) { showing.add(false); continue; }
            if (c == 0x14) { if (!showing.isEmpty()) showing.set(showing.size() - 1, true); continue; }
            if (c == 0x15) { if (!showing.isEmpty()) showing.remove(showing.size() - 1); continue; }
            boolean hidden = false;
            for (Boolean s : showing) if (!s) hidden = true;
            if (hidden && c != '\r' && c != 0x07) continue;
            if (c == '\r' || c == 0x07) {
                long fc = i < cpFc.size() ? cpFc.get(i) : -1;
                int f = ttp.flags(fc);
                if (c == 0x07) {
                    if ((f & 2) != 0) {
                        if (!cellBuf.isEmpty() || para.length() > 0) {
                            cellBuf.add(para.toString());
                            cells.add(String.join("\n", cellBuf).trim());
                            cellBuf.clear();
                        }
                        rows.add(new ArrayList<>(cells));
                        cells.clear();
                    } else {
                        cellBuf.add(para.toString());
                        cells.add(String.join("\n", cellBuf).trim());
                        cellBuf.clear();
                    }
                } else if ((f & 1) != 0) {
                    cellBuf.add(para.toString());
                } else {
                    if (!cells.isEmpty()) rows.add(new ArrayList<>(cells));
                    cells.clear();
                    for (String cb : cellBuf) emitPara(sb, cb);
                    cellBuf.clear();
                    if (!rows.isEmpty()) emitTable(sb, rows);
                    rows.clear();
                    emitPara(sb, para.toString());
                }
                para.setLength(0);
                continue;
            }
            switch (c) {
                case 0x0B: para.append('\n'); break;
                case 0x0C:
                    emitPara(sb, para.toString());
                    para.setLength(0);
                    sb.append("<hr class=\"pb\">");
                    break;
                case 0x09: para.append('\t'); break;
                case 0x1E: para.append('-'); break;
                default:
                    if (c >= 0x20) para.append(c);
                    break;
            }
        }
        if (!cells.isEmpty()) rows.add(new ArrayList<>(cells));
        if (!rows.isEmpty()) emitTable(sb, rows);
        for (String cb : cellBuf) emitPara(sb, cb);
        if (para.length() > 0) emitPara(sb, para.toString());
        sb.append("</div>");
        return sb.toString();
    }

    private static void emitPara(StringBuilder sb, String s) {
        sb.append("<p>");
        if (s.isEmpty()) sb.append("<br>");
        else {
            String e = Util.esc(s).replace("\n", "<br>").replace("\t", "&emsp;");
            if (e.contains("  ")) e = e.replace("  ", " &nbsp;");
            sb.append(e);
        }
        sb.append("</p>");
    }

    private static void emitTable(StringBuilder sb, List<List<String>> rows) {
        sb.append("<div class=\"tw\"><table class=\"bd\" style=\"width:100%\"><tbody>");
        for (List<String> r : rows) {
            sb.append("<tr>");
            for (String c : r) {
                sb.append("<td>").append(Util.esc(c).replace("\n", "<br>").replace("\t", "&emsp;")).append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</tbody></table></div>");
    }
}
