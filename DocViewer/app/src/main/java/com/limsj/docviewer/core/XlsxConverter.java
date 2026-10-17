package com.limsj.docviewer.core;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Excel (.xlsx) to HTML. Sheets are parsed with a streaming parser. */
public final class XlsxConverter {
    private final ZipRes zip;
    private final List<String> shared = new ArrayList<>();
    private final List<String> xfFormat = new ArrayList<>();
    private final List<String> xfCss = new ArrayList<>();
    private boolean date1904;

    private XlsxConverter(ZipRes zip) {
        this.zip = zip;
    }

    public static Doc convert(ZipRes zip) throws Exception {
        return new XlsxConverter(zip).run();
    }

    private Doc run() throws Exception {
        String wbPath = "xl/workbook.xml";
        String rel = zip.relByType("", "/officeDocument");
        if (rel != null && zip.has(rel)) wbPath = rel;
        XNode wb = zip.xml(wbPath);
        if (wb == null) throw new Doc.UserError("통합 문서를 찾을 수 없습니다.");
        XNode pr = wb.find("workbookPr");
        if (pr != null) {
            String d = pr.attr("date1904", "");
            date1904 = d.equals("1") || d.equalsIgnoreCase("true");
        }
        Map<String, String> rels = zip.rels(wbPath);
        String dir = Util.dirOf(wbPath);
        String ss = null, st = null;
        for (String v : rels.values()) {
            if (v == null) continue;
            String lv = v.toLowerCase(java.util.Locale.ROOT);
            if (lv.endsWith("sharedstrings.xml")) ss = v;
            else if (lv.endsWith("styles.xml")) st = v;
        }
        if (ss == null) ss = Util.resolve(dir, "sharedStrings.xml");
        if (st == null) st = Util.resolve(dir, "styles.xml");
        loadShared(ss);
        loadStyles(st);

        List<Sheets.Sheet> out = new ArrayList<>();
        for (XNode s : wb.findAll("sheet")) {
            String state = s.attr("state", "visible");
            if (!state.equals("visible")) continue;
            String target = rels.get(s.rel("id"));
            if (target == null || !zip.has(target)) continue;
            Sheets.Sheet sh = new Sheets.Sheet();
            sh.name = s.attr("name", "Sheet");
            try {
                parseSheet(target, sh);
            } catch (OutOfMemoryError e) {
                sh.truncated = true;
            }
            out.add(sh);
        }
        // Chartsheets etc. are skipped; if every sheet was hidden show them anyway.
        if (out.isEmpty()) {
            for (XNode s : wb.findAll("sheet")) {
                String target = rels.get(s.rel("id"));
                if (target == null || !zip.has(target)) continue;
                Sheets.Sheet sh = new Sheets.Sheet();
                sh.name = s.attr("name", "Sheet");
                parseSheet(target, sh);
                out.add(sh);
            }
        }
        return new Doc(Sheets.render(out), zip, zip);
    }

    private void loadShared(String path) throws Exception {
        InputStream in = zip.open(path);
        if (in == null) return;
        try {
            XmlPullParser p = XNode.newParser(in);
            StringBuilder cur = null;
            int depthRph = 0;
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = p.getName();
                    if (n.equals("si")) cur = new StringBuilder();
                    else if (n.equals("rPh") || n.equals("phoneticPr")) depthRph++;
                    else if (n.equals("t") && cur != null && depthRph == 0) cur.append(p.nextText());
                } else if (ev == XmlPullParser.END_TAG) {
                    String n = p.getName();
                    if (n.equals("si") && cur != null) {
                        shared.add(cur.toString());
                        cur = null;
                    } else if (n.equals("rPh") || n.equals("phoneticPr")) depthRph--;
                }
                ev = p.next();
            }
        } finally {
            in.close();
        }
    }

    private void loadStyles(String path) {
        try {
            XNode st = zip.xml(path);
            if (st == null) return;
            Map<Integer, String> fmts = new HashMap<>();
            for (XNode f : st.findAll("numFmt")) fmts.put(f.intAttr("numFmtId", -1), f.attr("formatCode"));
            List<String> fontCss = new ArrayList<>();
            XNode fonts = st.find("fonts");
            if (fonts != null) for (XNode f : fonts.children("font")) {
                StringBuilder c = new StringBuilder();
                XNode b = f.child("b");
                if (b != null && !"0".equals(b.attr("val")) && !"false".equals(b.attr("val"))) c.append("font-weight:bold;");
                XNode i = f.child("i");
                if (i != null && !"0".equals(i.attr("val")) && !"false".equals(i.attr("val"))) c.append("font-style:italic;");
                XNode u = f.child("u");
                if (u != null && !"none".equals(u.attr("val"))) c.append("text-decoration:underline;");
                XNode col = f.child("color");
                if (col != null) {
                    String hc = Util.hexColor(col.attr("rgb"));
                    if (hc != null && !hc.equals("#000000")) c.append("color:").append(hc).append(';');
                }
                XNode sz = f.child("sz");
                if (sz != null) {
                    double v = 11;
                    try {
                        v = Double.parseDouble(sz.attr("val", "11"));
                    } catch (Exception ignored) {
                    }
                    if (v > 0 && v != 11 && v < 200) c.append("font-size:").append(Util.fmt(v)).append("pt;");
                }
                fontCss.add(c.toString());
            }
            List<String> fillCss = new ArrayList<>();
            XNode fills = st.find("fills");
            if (fills != null) for (XNode f : fills.children("fill")) {
                String c = "";
                XNode pf = f.child("patternFill");
                if (pf != null && !"none".equals(pf.attr("patternType", "none"))) {
                    XNode fg = pf.child("fgColor");
                    String hc = fg != null ? Util.hexColor(fg.attr("rgb")) : null;
                    if (hc == null && fg != null && fg.attr("theme") != null) hc = themeTint(fg.intAttr("theme", -1));
                    if (hc != null) c = "background:" + hc + ";";
                }
                fillCss.add(c);
            }
            XNode xfs = st.find("cellXfs");
            if (xfs != null) for (XNode xf : xfs.children("xf")) {
                int id = xf.intAttr("numFmtId", 0);
                String f = fmts.get(id);
                xfFormat.add(f != null ? f : NumFmt.builtin(id));
                StringBuilder c = new StringBuilder();
                int fo = xf.intAttr("fontId", 0), fi = xf.intAttr("fillId", 0);
                if (fo >= 0 && fo < fontCss.size()) c.append(fontCss.get(fo));
                if (fi >= 0 && fi < fillCss.size()) c.append(fillCss.get(fi));
                XNode al = xf.child("alignment");
                if (al != null) {
                    String h = al.attr("horizontal");
                    if ("center".equals(h) || "centerContinuous".equals(h)) c.append("text-align:center;");
                    else if ("right".equals(h)) c.append("text-align:right;");
                    else if ("left".equals(h)) c.append("text-align:left;");
                    String v = al.attr("vertical");
                    if ("center".equals(v)) c.append("vertical-align:middle;");
                    else if ("top".equals(v)) c.append("vertical-align:top;");
                    if ("1".equals(al.attr("wrapText")) || "true".equals(al.attr("wrapText"))) c.append("white-space:pre-wrap;");
                }
                xfCss.add(c.length() > 0 ? c.toString() : null);
            }
        } catch (Exception ignored) {
        }
    }

    /** Approximation for theme-colored fills (standard Office palette). */
    private static String themeTint(int idx) {
        String[] t = {"#FFFFFF", "#000000", "#E7E6E6", "#44546A", "#4472C4", "#ED7D31", "#A5A5A5", "#FFC000", "#5B9BD5", "#70AD47"};
        if (idx < 0 || idx >= t.length || idx == 0) return null;
        return t[idx];
    }

    private void parseSheet(String path, Sheets.Sheet sh) throws Exception {
        InputStream in = zip.open(path);
        if (in == null) return;
        try {
            XmlPullParser p = XNode.newParser(in);
            int ev = p.getEventType();
            int rowIdx = -1, colIdx = -1;
            String cType = null, cRef;
            int cStyle = 0;
            StringBuilder val = null;
            StringBuilder inl = null;
            boolean stop = false;
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = p.getName();
                    switch (n) {
                        case "row": {
                            int r = Util.parseInt(p.getAttributeValue(null, "r"), 0);
                            rowIdx = r > 0 ? r - 1 : rowIdx + 1;
                            colIdx = -1;
                            if (rowIdx >= Sheets.MAX_ROWS) {
                                sh.truncated = true;
                                stop = true;
                            }
                            break;
                        }
                        case "c": {
                            cRef = p.getAttributeValue(null, "r");
                            int[] rc = Sheets.ref(cRef);
                            if (rc != null) {
                                colIdx = rc[1];
                                if (rowIdx < 0) rowIdx = rc[0];
                            } else colIdx++;
                            cType = p.getAttributeValue(null, "t");
                            cStyle = Util.parseInt(p.getAttributeValue(null, "s"), 0);
                            val = null;
                            inl = null;
                            break;
                        }
                        case "v":
                            val = new StringBuilder(p.nextText());
                            ev = p.next();
                            continue;
                        case "is":
                            inl = new StringBuilder();
                            break;
                        case "t":
                            if (inl != null) {
                                inl.append(p.nextText());
                                ev = p.next();
                                continue;
                            }
                            break;
                        case "col": {
                            int min = Util.parseInt(p.getAttributeValue(null, "min"), 0);
                            int max = Util.parseInt(p.getAttributeValue(null, "max"), 0);
                            String w = p.getAttributeValue(null, "width");
                            String hidden = p.getAttributeValue(null, "hidden");
                            if (min > 0 && w != null) {
                                double wd = 8.43;
                                try {
                                    wd = Double.parseDouble(w);
                                } catch (Exception ignored) {
                                }
                                int px = ("1".equals(hidden) || "true".equals(hidden)) ? 0 : (int) Math.round(wd * 7 + 5);
                                px = Math.min(px, 600);
                                for (int c = min; c <= Math.min(max, Sheets.MAX_COLS); c++) sh.colWidthPx.put(c - 1, px);
                            }
                            break;
                        }
                        case "mergeCell": {
                            String ref = p.getAttributeValue(null, "ref");
                            if (ref != null && ref.contains(":")) {
                                int[] a = Sheets.ref(ref.substring(0, ref.indexOf(':')));
                                int[] b = Sheets.ref(ref.substring(ref.indexOf(':') + 1));
                                if (a != null && b != null) sh.merges.add(new int[]{a[0], a[1], b[0], b[1]});
                            }
                            break;
                        }
                        default:
                            break;
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    String n = p.getName();
                    if (n.equals("c") && !stop) {
                        addCell(sh, rowIdx, colIdx, cType, cStyle, val, inl);
                    }
                }
                if (stop) {
                    // skip quickly to end of sheetData, then keep reading mergeCells
                    while (ev != XmlPullParser.END_DOCUMENT && !(ev == XmlPullParser.END_TAG && "sheetData".equals(p.getName()))) ev = p.next();
                    stop = false;
                    continue;
                }
                ev = p.next();
            }
        } finally {
            in.close();
        }
    }

    private void addCell(Sheets.Sheet sh, int r, int c, String t, int s, StringBuilder val, StringBuilder inl) {
        if (r < 0 || c < 0) return;
        String css = s >= 0 && s < xfCss.size() ? xfCss.get(s) : null;
        Sheets.Cell cell = new Sheets.Cell();
        cell.css = css;
        String v = val != null ? val.toString() : null;
        if ("s".equals(t)) {
            int i = Util.parseInt(v, -1);
            cell.text = i >= 0 && i < shared.size() ? shared.get(i) : "";
        } else if ("inlineStr".equals(t)) {
            cell.text = inl != null ? inl.toString() : "";
        } else if ("str".equals(t) || "e".equals(t)) {
            cell.text = v;
        } else if ("b".equals(t)) {
            cell.text = "1".equals(v) ? "TRUE" : "FALSE";
            cell.number = true;
        } else if (v != null && !v.isEmpty()) {
            try {
                double d = Double.parseDouble(v);
                String f = s >= 0 && s < xfFormat.size() ? xfFormat.get(s) : null;
                cell.text = NumFmt.format(d, f, date1904);
                cell.number = true;
            } catch (NumberFormatException e) {
                cell.text = v;
            }
        }
        if (cell.text == null && css == null) return;
        sh.put(r, c, cell);
    }
}
