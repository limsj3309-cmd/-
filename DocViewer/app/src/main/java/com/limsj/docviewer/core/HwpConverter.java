package com.limsj.docviewer.core;

import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** Hangul 5.x binary (.hwp) to HTML. */
public final class HwpConverter {
    private static final int TAG_BEGIN = 16;
    private static final int BIN_DATA = TAG_BEGIN + 2;
    private static final int CHAR_SHAPE = TAG_BEGIN + 5;
    private static final int PARA_SHAPE = TAG_BEGIN + 9;
    private static final int PARA_HEADER = TAG_BEGIN + 50;
    private static final int PARA_TEXT = TAG_BEGIN + 51;
    private static final int PARA_CHAR_SHAPE = TAG_BEGIN + 52;
    private static final int CTRL_HEADER = TAG_BEGIN + 55;
    private static final int LIST_HEADER = TAG_BEGIN + 56;
    private static final int TABLE = TAG_BEGIN + 61;
    private static final int SHAPE_PICTURE = TAG_BEGIN + 69;
    private static final int DISTRIBUTE_DOC_DATA = TAG_BEGIN + 12;

    private static final long LIMIT = 256L * 1024 * 1024;

    private final Cfb cfb;
    private boolean compressed;
    private final List<String> charCss = new ArrayList<>();
    private final List<String> paraAlign = new ArrayList<>();
    private final List<String> binNames = new ArrayList<>(); // 1-based id -> stream name
    private final List<Integer> binCompress = new ArrayList<>();
    private final StringBuilder sb = new StringBuilder(65536);

    private HwpConverter(Cfb cfb) {
        this.cfb = cfb;
    }

    public static Doc convert(Cfb cfb) throws Exception {
        return new HwpConverter(cfb).run();
    }

    // ---------- records ----------

    private static final class Rec {
        int tag, level;
        byte[] data;
        final List<Rec> kids = new ArrayList<>(2);
    }

    private static List<Rec> records(byte[] b) {
        List<Rec> out = new ArrayList<>();
        int o = 0;
        while (o + 4 <= b.length) {
            int h = Util.i32(b, o);
            o += 4;
            Rec r = new Rec();
            r.tag = h & 0x3FF;
            r.level = (h >>> 10) & 0x3FF;
            int size = (h >>> 20) & 0xFFF;
            if (size == 0xFFF) {
                if (o + 4 > b.length) break;
                size = Util.i32(b, o);
                o += 4;
            }
            if (size < 0 || o + size > b.length) size = Math.max(0, b.length - o);
            r.data = new byte[size];
            System.arraycopy(b, o, r.data, 0, size);
            o += size;
            out.add(r);
        }
        return out;
    }

    private static Rec tree(List<Rec> flat) {
        Rec root = new Rec();
        root.level = -1;
        List<Rec> stack = new ArrayList<>();
        stack.add(root);
        for (Rec r : flat) {
            while (stack.size() > 1 && stack.get(stack.size() - 1).level >= r.level) stack.remove(stack.size() - 1);
            stack.get(stack.size() - 1).kids.add(r);
            stack.add(r);
        }
        return root;
    }

    private byte[] stream(String name) throws Exception {
        byte[] b = cfb.read(name);
        if (b == null) return null;
        return compressed ? Util.inflateRaw(b, LIMIT) : b;
    }

    // ---------- main ----------

    private Doc run() throws Exception {
        byte[] fh = cfb.read("FileHeader");
        if (fh == null || fh.length < 40) throw new Doc.UserError("한글 문서 헤더를 읽을 수 없습니다.");
        String sig = new String(fh, 0, 17, "US-ASCII");
        if (!sig.startsWith("HWP Document File")) throw new Doc.UserError("한글 5.0 형식이 아닙니다. (한글 97 이하 문서는 지원하지 않습니다)");
        int props = Util.i32(fh, 36);
        compressed = (props & 1) != 0;
        if ((props & 2) != 0) throw new Doc.UserError("암호가 설정된 한글 문서는 열 수 없습니다.");
        boolean distribute = (props & 4) != 0;

        byte[] info = stream("DocInfo");
        if (info != null) docInfo(records(info));

        sb.append("<div class=\"page\">");
        int sections = 0;
        for (int i = 0; i < 1000; i++) {
            byte[] sec;
            if (distribute) {
                byte[] raw = cfb.read("ViewText/Section" + i);
                if (raw == null) break;
                sec = decryptDistribution(raw);
            } else {
                sec = stream("BodyText/Section" + i);
                if (sec == null) break;
            }
            if (i > 0) sb.append("<hr class=\"pb\">");
            Rec root = tree(records(sec));
            paras(root.kids);
            sections++;
        }
        if (sections == 0) throw new Doc.UserError("한글 문서 본문을 찾을 수 없습니다.");
        sb.append("</div>");
        Doc.Res res = path -> {
            if (!path.startsWith("bin/")) return null;
            int id = Util.parseInt(path.substring(4), -1);
            if (id < 1 || id > binNames.size() || binNames.get(id - 1) == null) return null;
            byte[] raw = cfb.read(binNames.get(id - 1));
            if (raw == null) return null;
            int mode = binCompress.get(id - 1);
            boolean comp = mode == 1 || (mode == 0 && compressed);
            if (comp) {
                try {
                    byte[] d = Util.inflateRaw(raw, LIMIT);
                    if (d.length > 0) return d;
                } catch (Exception ignored) {
                }
            }
            return raw;
        };
        return new Doc(Html.page("hwp", null, sb.toString(), null), res, cfb);
    }

    /** Distribution (배포용) documents: AES-128/ECB with a key hidden in the first record. */
    private byte[] decryptDistribution(byte[] raw) throws Exception {
        if (raw.length < 4 + 256) throw new Doc.UserError("배포용 문서를 해독할 수 없습니다.");
        int h = Util.i32(raw, 0);
        int tag = h & 0x3FF;
        int size = (h >>> 20) & 0xFFF;
        if (tag != DISTRIBUTE_DOC_DATA || size != 256) throw new Doc.UserError("배포용 문서를 해독할 수 없습니다.");
        byte[] data = new byte[256];
        System.arraycopy(raw, 4, data, 0, 256);
        int seed = Util.i32(data, 0);
        // MSVC rand() based XOR stream
        byte[] xor = new byte[256];
        int state = seed;
        int i = 0;
        while (i < 256) {
            state = state * 214013 + 2531011;
            int a = (state >>> 16) & 0x7FFF;
            state = state * 214013 + 2531011;
            int n = ((state >>> 16) & 0x7FFF & 0xF) + 1;
            for (int k = 0; k < n && i < 256; k++) xor[i++] = (byte) (a & 0xFF);
        }
        byte[] dec = new byte[256];
        for (int k = 0; k < 256; k++) dec[k] = (byte) (data[k] ^ xor[k]);
        int off = 4 + (seed & 0xF);
        byte[] key = new byte[16];
        System.arraycopy(dec, off, key, 0, 16);
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        int bodyLen = (raw.length - 260) / 16 * 16;
        byte[] body = c.doFinal(raw, 260, bodyLen);
        return compressed ? Util.inflateRaw(body, LIMIT) : body;
    }

    private void docInfo(List<Rec> recs) {
        for (Rec r : recs) {
            byte[] d = r.data;
            switch (r.tag) {
                case BIN_DATA: {
                    int p = Util.u16(d, 0);
                    int type = p & 0xF;
                    String name = null;
                    if (type == 1 || type == 2) {
                        int id = Util.u16(d, 2);
                        int len = Util.u16(d, 4);
                        String ext = Util.utf16(d, 6, len);
                        name = String.format("BinData/BIN%04X.%s", id, ext);
                        if (!cfb.has(name)) name = String.format("BinData/BIN%04x.%s", id, ext);
                        if (!cfb.has(name)) name = null;
                    }
                    binNames.add(name);
                    binCompress.add((p >> 4) & 3);
                    break;
                }
                case CHAR_SHAPE: {
                    StringBuilder c = new StringBuilder();
                    if (d.length >= 56) {
                        int size = Util.i32(d, 42);
                        int prop = Util.i32(d, 46);
                        int color = Util.i32(d, 52);
                        if (size > 0 && size != 1000 && size < 40000) c.append("font-size:").append(Util.fmt(size / 100.0)).append("pt;");
                        if ((prop & 1) != 0) c.append("font-style:italic;");
                        if ((prop & 2) != 0) c.append("font-weight:bold;");
                        String deco = "";
                        if (((prop >> 2) & 3) == 1) deco += " underline";
                        if (((prop >> 18) & 7) != 0) deco += " line-through";
                        if (!deco.isEmpty()) c.append("text-decoration:").append(deco.trim()).append(';');
                        if ((prop & (1 << 15)) != 0) c.append("vertical-align:super;font-size:smaller;");
                        else if ((prop & (1 << 16)) != 0) c.append("vertical-align:sub;font-size:smaller;");
                        if ((color & 0xFFFFFF) != 0) c.append("color:").append(Util.colorRef(color)).append(';');
                    }
                    charCss.add(c.toString());
                    break;
                }
                case PARA_SHAPE: {
                    int p = Util.i32(d, 0);
                    int a = (p >> 2) & 7;
                    String css = null;
                    if (a == 0 || a == 4 || a == 5) css = "justify";
                    else if (a == 2) css = "right";
                    else if (a == 3) css = "center";
                    paraAlign.add(css);
                    break;
                }
                default:
                    break;
            }
        }
    }

    // ---------- body ----------

    private static int chid(char a, char b, char c, char d) {
        return (a << 24) | (b << 16) | (c << 8) | d;
    }

    private static final int TBL = chid('t', 'b', 'l', ' ');
    private static final int GSO = chid('g', 's', 'o', ' ');
    private static final int EQED = chid('e', 'q', 'e', 'd');
    private static final int HEAD = chid('h', 'e', 'a', 'd');
    private static final int FOOT = chid('f', 'o', 'o', 't');
    private static final int FN = chid('f', 'n', ' ', ' ');
    private static final int EN = chid('e', 'n', ' ', ' ');

    private static boolean isExtended(int c) {
        return c == 1 || c == 2 || c == 3 || c == 11 || c == 12 || (c >= 14 && c <= 18) || (c >= 21 && c <= 23);
    }

    private static boolean isCharCtrl(int c) {
        return c == 0 || c == 10 || c == 13 || (c >= 24 && c <= 31);
    }

    private void paras(List<Rec> recs) {
        for (Rec r : recs) if (r.tag == PARA_HEADER) para(r);
    }

    private void para(Rec p) {
        Rec text = null, shapes = null;
        List<Rec> ctrls = new ArrayList<>();
        for (Rec k : p.kids) {
            if (k.tag == PARA_TEXT) text = k;
            else if (k.tag == PARA_CHAR_SHAPE) shapes = k;
            else if (k.tag == CTRL_HEADER) ctrls.add(k);
        }
        int shapeId = Util.u16(p.data, 8);
        String align = shapeId >= 0 && shapeId < paraAlign.size() ? paraAlign.get(shapeId) : null;

        // char shape runs: (pos, id) pairs
        int[] pos = new int[0], ids = new int[0];
        if (shapes != null) {
            int n = shapes.data.length / 8;
            pos = new int[n];
            ids = new int[n];
            for (int i = 0; i < n; i++) {
                pos[i] = Util.i32(shapes.data, i * 8);
                ids[i] = Util.i32(shapes.data, i * 8 + 4);
            }
        }
        boolean[] used = new boolean[ctrls.size()];
        openP(align);
        int mark = sb.length();
        if (text != null) {
            byte[] d = text.data;
            int n = d.length / 2;
            int si = -1;
            String curCss = null;
            boolean spanOpen = false;
            StringBuilder buf = new StringBuilder();
            int i = 0;
            while (i < n) {
                // char shape switch
                int nsi = si;
                while (nsi + 1 < pos.length && pos[nsi + 1] <= i) nsi++;
                if (nsi != si) {
                    si = nsi;
                    flush(buf);
                    String css = si >= 0 && ids[si] >= 0 && ids[si] < charCss.size() ? charCss.get(ids[si]) : null;
                    if (css != null && css.isEmpty()) css = null;
                    if (!eq(css, curCss)) {
                        if (spanOpen) sb.append("</span>");
                        spanOpen = css != null;
                        if (spanOpen) sb.append("<span style=\"").append(css).append("\">");
                        curCss = css;
                    }
                }
                int c = Util.u16(d, i * 2);
                if (c >= 32) {
                    buf.append((char) c);
                    i++;
                    continue;
                }
                if (isCharCtrl(c)) {
                    switch (c) {
                        case 10: flush(buf); sb.append("<br>"); break;
                        case 24: buf.append('-'); break;
                        case 30: case 31: buf.append(' '); break;
                        default: break;
                    }
                    i++;
                    continue;
                }
                // inline / extended controls occupy 8 WCHARs
                if (c == 9) buf.append('\t');
                if (isExtended(c) && i + 3 <= n) {
                    int id = (Util.u16(d, (i + 2) * 2) << 16) | Util.u16(d, (i + 1) * 2);
                    Rec ctrl = null;
                    for (int k = 0; k < ctrls.size(); k++) {
                        if (!used[k] && Util.i32(ctrls.get(k).data, 0) == id) {
                            used[k] = true;
                            ctrl = ctrls.get(k);
                            break;
                        }
                    }
                    if (ctrl != null) {
                        flush(buf);
                        if (control(ctrl, id, align, spanOpen)) {
                            mark = sb.length();
                            if (curCss != null) sb.append("<span style=\"").append(curCss).append("\">");
                            mark = sb.length();
                        }
                    }
                }
                i += 8;
            }
            flush(buf);
            if (spanOpen) sb.append("</span>");
        }
        // controls never referenced from text (rare) — render tables/pictures anyway
        for (int k = 0; k < ctrls.size(); k++) {
            if (used[k]) continue;
            int id = Util.i32(ctrls.get(k).data, 0);
            if (id == TBL || id == GSO) control(ctrls.get(k), id, align, false);
        }
        if (sb.length() == mark) sb.append("<br>");
        sb.append("</p>");
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private void flush(StringBuilder buf) {
        if (buf.length() == 0) return;
        String e = Util.esc(buf.toString());
        if (e.indexOf('\t') >= 0) e = e.replace("\t", "&emsp;");
        if (e.contains("  ")) e = e.replace("  ", " &nbsp;");
        sb.append(e);
        buf.setLength(0);
    }

    private void openP(String align) {
        sb.append("<p");
        if (align != null) sb.append(" style=\"text-align:").append(align).append('"');
        sb.append('>');
    }

    /** Renders a control. Returns true if the current paragraph was closed and reopened. */
    private boolean control(Rec ctrl, int id, String align, boolean spanOpen) {
        if (id == TBL) {
            if (spanOpen) sb.append("</span>");
            sb.append("</p>");
            table(ctrl);
            openP(align);
            return true;
        }
        if (id == GSO) {
            Rec pic = findTag(ctrl, SHAPE_PICTURE);
            if (pic != null) {
                int bin = Util.u16(pic.data, 71);
                long w = Util.u32(ctrl.data, 16);
                if (bin >= 1 && bin <= binNames.size() && binNames.get(bin - 1) != null) {
                    String name = binNames.get(bin - 1);
                    String ext = Util.ext(name);
                    if (Util.imageMime(ext) != null) {
                        sb.append("<img loading=\"lazy\" src=\"res/bin/").append(bin).append('"');
                        if (w > 0 && w < 10_000_000) sb.append(" style=\"width:").append(w / 75).append("px\"");
                        sb.append('>');
                    } else sb.append("<span style=\"color:#999\">[그림]</span>");
                }
            }
            if (hasParas(ctrl)) {
                if (spanOpen) sb.append("</span>");
                sb.append("</p><div style=\"border:1px solid #ccc;padding:4px 6px;margin:4px 0\">");
                nested(ctrl);
                sb.append("</div>");
                openP(align);
                return true;
            }
            return false;
        }
        if (id == EQED) {
            for (Rec k : ctrl.kids) {
                if (k.tag == TAG_BEGIN + 72 && k.data.length > 6) {
                    int len = Util.u16(k.data, 4);
                    String script = Util.utf16(k.data, 6, len);
                    sb.append("<i>");
                    Util.escTo(sb, script);
                    sb.append("</i>");
                }
            }
            return false;
        }
        // headers, footers, footnotes, fields, etc. are skipped in this viewer
        if (id == HEAD || id == FOOT || id == FN || id == EN) return false;
        return false;
    }

    private static Rec findTag(Rec r, int tag) {
        for (Rec k : r.kids) {
            if (k.tag == tag) return k;
            Rec f = findTag(k, tag);
            if (f != null) return f;
        }
        return null;
    }

    private static boolean hasParas(Rec r) {
        for (Rec k : r.kids) {
            if (k.tag == PARA_HEADER) return true;
            if (k.tag != CTRL_HEADER && hasParas(k)) return true;
        }
        return false;
    }

    private void nested(Rec r) {
        for (Rec k : r.kids) {
            if (k.tag == PARA_HEADER) para(k);
            else if (k.tag != CTRL_HEADER) nested(k);
        }
    }

    private static final class HCell {
        int row, col, rowSpan = 1, colSpan = 1;
        long width;
        int vAlign;
        final List<Rec> paras = new ArrayList<>();
    }

    private void table(Rec ctrl) {
        Rec tbl = null;
        List<HCell> cells = new ArrayList<>();
        HCell cur = null;
        for (Rec k : ctrl.kids) {
            if (k.tag == TABLE) tbl = k;
            else if (k.tag == LIST_HEADER) {
                cur = new HCell();
                byte[] d = k.data;
                if (d.length >= 16) {
                    cur.col = Util.u16(d, 8);
                    cur.row = Util.u16(d, 10);
                    cur.colSpan = Math.max(1, Util.u16(d, 12));
                    cur.rowSpan = Math.max(1, Util.u16(d, 14));
                    cur.width = Util.u32(d, 16);
                    int prop = Util.i32(d, 2);
                    cur.vAlign = (prop >> 21) & 3;
                } else {
                    cur.row = -1;
                }
                cells.add(cur);
            } else if (k.tag == PARA_HEADER && cur != null) {
                cur.paras.add(k);
            }
        }
        int rows = tbl != null ? Util.u16(tbl.data, 4) : 0;
        // fall back to sequential placement when cell addresses are missing
        int maxRow = rows - 1;
        for (HCell c : cells) maxRow = Math.max(maxRow, c.row);
        if (maxRow > 5000) maxRow = 5000;
        long total = 0;
        for (HCell c : cells) if (c.row == 0) total += c.width;
        sb.append("<div class=\"tw\"><table class=\"bd\" style=\"width:100%\"><tbody>");
        for (int r = 0; r <= maxRow; r++) {
            sb.append("<tr>");
            List<HCell> row = new ArrayList<>();
            for (HCell c : cells) if (c.row == r) row.add(c);
            row.sort((a, b) -> Integer.compare(a.col, b.col));
            for (HCell c : row) cell(c, total);
            sb.append("</tr>");
        }
        boolean orphan = false;
        for (HCell c : cells) if (c.row < 0) orphan = true;
        if (orphan) {
            sb.append("<tr>");
            for (HCell c : cells) if (c.row < 0) cell(c, 0);
            sb.append("</tr>");
        }
        sb.append("</tbody></table></div>");
    }

    private void cell(HCell c, long total) {
        sb.append("<td");
        if (c.colSpan > 1) sb.append(" colspan=\"").append(c.colSpan).append('"');
        if (c.rowSpan > 1) sb.append(" rowspan=\"").append(c.rowSpan).append('"');
        StringBuilder css = new StringBuilder();
        if (total > 0 && c.width > 0) css.append("width:").append(Util.fmt(Math.min(100.0, 100.0 * c.width / total))).append("%;");
        if (c.vAlign == 1) css.append("vertical-align:middle;");
        else if (c.vAlign == 2) css.append("vertical-align:bottom;");
        if (css.length() > 0) sb.append(" style=\"").append(css).append('"');
        sb.append('>');
        for (Rec p : c.paras) para(p);
        sb.append("</td>");
    }
}
