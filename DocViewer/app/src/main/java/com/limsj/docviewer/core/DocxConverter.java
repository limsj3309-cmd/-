package com.limsj.docviewer.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Word (.docx) to HTML. */
public final class DocxConverter {
    private final ZipRes zip;
    private Map<String, String> rels = new HashMap<>();
    private final Map<String, Integer> headingLevel = new HashMap<>();
    private final Map<String, XNode> styleNumPr = new HashMap<>();
    private final Map<String, String> styleJc = new HashMap<>();
    private final Map<String, String> numToAbstract = new HashMap<>();
    private final Map<String, XNode> abstractNums = new HashMap<>();
    private final Map<String, int[]> counters = new HashMap<>();
    private final Map<String, XNode> footnotes = new HashMap<>();
    private final List<String> usedNotes = new ArrayList<>();
    private int pageWidthPx = 0;
    private final StringBuilder sb = new StringBuilder(65536);

    private DocxConverter(ZipRes zip) {
        this.zip = zip;
    }

    public static Doc convert(ZipRes zip) throws Exception {
        return new DocxConverter(zip).run();
    }

    private Doc run() throws Exception {
        String main = "word/document.xml";
        try {
            XNode ct = zip.xml("[Content_Types].xml");
            if (ct != null) for (XNode o : ct.findAll("Override")) {
                String t = o.attr("ContentType", "");
                if (t.contains("wordprocessingml.document.main") || t.contains("wordprocessingml.template.main")) {
                    main = Util.resolve("", o.attr("PartName"));
                }
            }
        } catch (Exception ignored) {
        }
        XNode doc = zip.xml(main);
        if (doc == null) throw new Doc.UserError("문서 본문을 찾을 수 없습니다.");
        rels = zip.rels(main);
        loadStyles(rels);
        loadNumbering(rels);
        loadFootnotes(rels);

        XNode body = doc.find("body");
        String baseCss = "";
        if (body != null) {
            XNode sect = body.child("sectPr");
            if (sect != null) {
                XNode pg = sect.child("pgSz");
                XNode mar = sect.child("pgMar");
                if (pg != null) {
                    int w = pg.intAttr("w", 0) - (mar != null ? mar.intAttr("left", 0) + mar.intAttr("right", 0) : 0);
                    if (w > 0) pageWidthPx = w / 15;
                }
            }
        }
        sb.append("<div class=\"page\">");
        if (body != null) blocks(body);
        if (!usedNotes.isEmpty()) {
            sb.append("<div class=\"fn\">");
            for (int i = 0; i < usedNotes.size(); i++) {
                XNode fn = footnotes.get(usedNotes.get(i));
                if (fn == null) continue;
                sb.append("<div><sup>").append(i + 1).append("</sup> ");
                StringBuilder save = new StringBuilder();
                for (XNode p : fn.findAll("p")) save.append(p.allText()).append(' ');
                Util.escTo(sb, save.toString().trim());
                sb.append("</div>");
            }
            sb.append("</div>");
        }
        sb.append("</div>");
        return new Doc(Html.page("docx", baseCss, sb.toString(), null), zip, zip);
    }

    // ---------- styles / numbering ----------

    private void loadStyles(Map<String, String> rels) {
        try {
            String path = "word/styles.xml";
            for (Map.Entry<String, String> e : relTypes(rels, "styles").entrySet()) path = e.getValue();
            XNode st = zip.xml(path);
            if (st == null) return;
            Map<String, XNode> byId = new HashMap<>();
            for (XNode s : st.children("styles").isEmpty() ? st.findAll("style") : st.child("styles").children("style")) {
                byId.put(s.attr("styleId"), s);
            }
            for (XNode s : byId.values()) {
                String id = s.attr("styleId");
                if (id == null) continue;
                XNode cur = s;
                for (int depth = 0; cur != null && depth < 8; depth++) {
                    XNode nm = cur.child("name");
                    String name = nm != null ? nm.attr("val", "").toLowerCase(Locale.ROOT) : "";
                    if (!headingLevel.containsKey(id)) {
                        if (name.startsWith("heading ")) {
                            int lvl = Util.parseInt(name.substring(8), 0);
                            if (lvl >= 1 && lvl <= 6) headingLevel.put(id, lvl);
                        } else if (name.equals("title")) headingLevel.put(id, 1);
                    }
                    XNode ppr = cur.child("pPr");
                    if (ppr != null) {
                        if (!styleNumPr.containsKey(id) && ppr.child("numPr") != null) styleNumPr.put(id, ppr.child("numPr"));
                        if (!styleJc.containsKey(id) && ppr.child("jc") != null) styleJc.put(id, ppr.child("jc").attr("val"));
                    }
                    XNode based = cur.child("basedOn");
                    cur = based != null ? byId.get(based.attr("val")) : null;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private Map<String, String> relTypes(Map<String, String> rels, String name) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> e : rels.entrySet()) {
            String v = e.getValue();
            if (v != null && v.toLowerCase(Locale.ROOT).endsWith("/" + name + ".xml")) out.put(e.getKey(), v);
        }
        return out;
    }

    private void loadNumbering(Map<String, String> rels) {
        try {
            String path = "word/numbering.xml";
            for (String v : relTypes(rels, "numbering").values()) path = v;
            XNode nx = zip.xml(path);
            if (nx == null) return;
            for (XNode a : nx.findAll("abstractNum")) abstractNums.put(a.attr("abstractNumId"), a);
            for (XNode n : nx.findAll("num")) {
                XNode an = n.child("abstractNumId");
                if (an != null) numToAbstract.put(n.attr("numId"), an.attr("val"));
            }
        } catch (Exception ignored) {
        }
    }

    private void loadFootnotes(Map<String, String> rels) {
        try {
            String path = "word/footnotes.xml";
            for (String v : relTypes(rels, "footnotes").values()) path = v;
            XNode fx = zip.xml(path);
            if (fx == null) return;
            for (XNode f : fx.findAll("footnote")) footnotes.put(f.attr("id"), f);
        } catch (Exception ignored) {
        }
    }

    private XNode level(String numId, int ilvl) {
        XNode an = abstractNums.get(numToAbstract.get(numId));
        if (an == null) return null;
        for (XNode l : an.children("lvl")) if (l.intAttr("ilvl", -1) == ilvl) return l;
        // numStyleLink indirection
        return null;
    }

    private String listLabel(String numId, int ilvl) {
        if (numId == null || "0".equals(numId)) return null;
        XNode lvl = level(numId, ilvl);
        if (lvl == null) return null;
        int[] c = counters.get(numId);
        if (c == null) {
            c = new int[9];
            for (int i = 0; i < 9; i++) {
                XNode l = level(numId, i);
                c[i] = (l != null && l.child("start") != null ? l.child("start").intAttr("val", 1) : 1) - 1;
            }
            counters.put(numId, c);
        }
        if (ilvl < 0 || ilvl > 8) ilvl = 0;
        c[ilvl]++;
        for (int i = ilvl + 1; i < 9; i++) {
            XNode l = level(numId, i);
            c[i] = (l != null && l.child("start") != null ? l.child("start").intAttr("val", 1) : 1) - 1;
        }
        String fmt = lvl.child("numFmt") != null ? lvl.child("numFmt").attr("val", "decimal") : "decimal";
        String text = lvl.child("lvlText") != null ? lvl.child("lvlText").attr("val", "") : "";
        if ("none".equals(fmt)) return "";
        if ("bullet".equals(fmt)) {
            if (text.isEmpty()) return "•";
            char ch = text.charAt(0);
            if (ch >= 0xE000 && ch <= 0xF8FF) {
                int low = ch & 0xFF;
                if (low == 0xA7) return "▪";
                if (low == 0xD8) return "➢";
                if (low == 0xFC) return "✓";
                if (low == 0x6F) return "◦";
                return "•";
            }
            return text;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '%' && i + 1 < text.length() && Character.isDigit(text.charAt(i + 1))) {
                int k = text.charAt(i + 1) - '1';
                i++;
                if (k >= 0 && k < 9) {
                    XNode l = level(numId, k);
                    String f = l != null && l.child("numFmt") != null ? l.child("numFmt").attr("val", "decimal") : "decimal";
                    out.append(NumText.format(Math.max(c[k], 1), f));
                }
            } else out.append(ch);
        }
        return out.toString();
    }

    // ---------- body ----------

    private void blocks(XNode parent) {
        for (XNode n : parent.kids()) {
            switch (n.name) {
                case "p": paragraph(n); break;
                case "tbl": table(n); break;
                case "sdt": {
                    XNode c = n.child("sdtContent");
                    if (c != null) blocks(c);
                    break;
                }
                case "customXml": case "ins": case "smartTag":
                    blocks(n);
                    break;
                default:
                    break;
            }
        }
    }

    private static boolean on(XNode n) {
        if (n == null) return false;
        String v = n.attr("val");
        return v == null || !(v.equals("0") || v.equals("false") || v.equals("off") || v.equals("none"));
    }

    private void paragraph(XNode p) {
        XNode ppr = p.child("pPr");
        String style = null, jc = null;
        XNode numPr = null;
        boolean pageBreakBefore = false;
        int indLeft = 0;
        if (ppr != null) {
            XNode ps = ppr.child("pStyle");
            if (ps != null) style = ps.attr("val");
            XNode j = ppr.child("jc");
            if (j != null) jc = j.attr("val");
            numPr = ppr.child("numPr");
            pageBreakBefore = on(ppr.child("pageBreakBefore"));
            XNode ind = ppr.child("ind");
            if (ind != null) indLeft = Util.parseInt(ind.attr("left", ind.attr("start")), 0);
        }
        if (jc == null && style != null) jc = styleJc.get(style);
        if (numPr == null && style != null) numPr = styleNumPr.get(style);
        if (pageBreakBefore) sb.append("<hr class=\"pb\">");

        Integer h = style != null ? headingLevel.get(style) : null;
        String tag = h != null ? "h" + h : "p";
        sb.append('<').append(tag);
        StringBuilder css = new StringBuilder();
        String align = align(jc);
        if (align != null) css.append("text-align:").append(align).append(';');
        if (indLeft > 0 && indLeft < 15000) css.append("margin-left:").append(indLeft / 15).append("px;");
        if (css.length() > 0) sb.append(" style=\"").append(css).append('"');
        sb.append('>');

        if (numPr != null) {
            XNode ni = numPr.child("numId");
            XNode il = numPr.child("ilvl");
            String label = listLabel(ni != null ? ni.attr("val") : null, il != null ? il.intAttr("val", 0) : 0);
            if (label != null && !label.isEmpty()) {
                Util.escTo(sb, label);
                sb.append("&nbsp; ");
            }
        }
        int mark = sb.length();
        inlines(p);
        if (sb.length() == mark) sb.append("<br>");
        sb.append("</").append(tag).append('>');
    }

    private static String align(String jc) {
        if (jc == null) return null;
        switch (jc) {
            case "center": return "center";
            case "right": case "end": return "right";
            case "both": case "distribute": return "justify";
            default: return null;
        }
    }

    private void inlines(XNode parent) {
        for (XNode n : parent.kids()) {
            switch (n.name) {
                case "r": run(n); break;
                case "hyperlink": {
                    String id = n.rel("id");
                    String target = id != null ? rels.get(id) : null;
                    if (target != null && target.startsWith("ext:") && target.length() > 4) {
                        sb.append("<a href=\"").append(Util.esc(target.substring(4))).append("\">");
                        inlines(n);
                        sb.append("</a>");
                    } else inlines(n);
                    break;
                }
                case "ins": case "smartTag": case "fldSimple": case "customXml": case "dir": case "bdo":
                    inlines(n);
                    break;
                case "sdt": {
                    XNode c = n.child("sdtContent");
                    if (c != null) inlines(c);
                    break;
                }
                case "oMath": case "oMathPara":
                    sb.append("<i>");
                    for (XNode t : n.findAll("t")) Util.escTo(sb, t.allText());
                    sb.append("</i>");
                    break;
                default:
                    break;
            }
        }
    }

    private void run(XNode r) {
        XNode rpr = r.child("rPr");
        StringBuilder css = new StringBuilder();
        boolean sup = false, sub = false;
        if (rpr != null) {
            if (on(rpr.child("b"))) css.append("font-weight:bold;");
            if (on(rpr.child("i"))) css.append("font-style:italic;");
            String deco = "";
            XNode u = rpr.child("u");
            if (u != null && on(u)) deco += " underline";
            if (on(rpr.child("strike")) || on(rpr.child("dstrike"))) deco += " line-through";
            if (!deco.isEmpty()) css.append("text-decoration:").append(deco.trim()).append(';');
            XNode sz = rpr.child("sz");
            if (sz != null) {
                int hp = sz.intAttr("val", 0);
                if (hp > 0 && hp < 1000) css.append("font-size:").append(Util.fmt(hp / 2.0)).append("pt;");
            }
            XNode col = rpr.child("color");
            if (col != null) {
                String c = Util.hexColor(col.attr("val"));
                if (c != null && !c.equals("#000000")) css.append("color:").append(c).append(';');
            }
            XNode hl = rpr.child("highlight");
            if (hl != null) {
                String c = highlight(hl.attr("val"));
                if (c != null) css.append("background:").append(c).append(';');
            }
            XNode shd = rpr.child("shd");
            if (shd != null && hl == null) {
                String c = Util.hexColor(shd.attr("fill"));
                if (c != null && !c.equals("#FFFFFF")) css.append("background:").append(c).append(';');
            }
            XNode va = rpr.child("vertAlign");
            if (va != null) {
                sup = "superscript".equals(va.attr("val"));
                sub = "subscript".equals(va.attr("val"));
            }
            if (on(rpr.child("vanish"))) return;
        }
        boolean span = css.length() > 0;
        if (span) sb.append("<span style=\"").append(css).append("\">");
        if (sup) sb.append("<sup>");
        if (sub) sb.append("<sub>");
        for (XNode n : r.kids()) {
            switch (n.name) {
                case "t": Util.escTo(sb, n.allText()); break;
                case "tab": sb.append("&emsp;"); break;
                case "br":
                    if ("page".equals(n.attr("type"))) sb.append("<hr class=\"pb\">");
                    else sb.append("<br>");
                    break;
                case "cr": sb.append("<br>"); break;
                case "noBreakHyphen": sb.append("&#8209;"); break;
                case "softHyphen": break;
                case "sym": {
                    int code = 0;
                    try {
                        code = Integer.parseInt(n.attr("char", "0"), 16);
                    } catch (Exception ignored) {
                    }
                    if (code >= 0xF000) sb.append("&#8226;");
                    else if (code > 0) sb.appendCodePoint(code);
                    break;
                }
                case "drawing": drawing(n); break;
                case "pict": case "object": pict(n); break;
                case "footnoteReference": {
                    usedNotes.add(n.attr("id"));
                    sb.append("<sup>").append(usedNotes.size()).append("</sup>");
                    break;
                }
                default: break;
            }
        }
        if (sub) sb.append("</sub>");
        if (sup) sb.append("</sup>");
        if (span) sb.append("</span>");
    }

    private static String highlight(String v) {
        if (v == null) return null;
        switch (v) {
            case "yellow": return "#FFFF00";
            case "green": return "#00FF00";
            case "cyan": return "#00FFFF";
            case "magenta": return "#FF00FF";
            case "blue": return "#0000FF";
            case "red": return "#FF0000";
            case "darkBlue": return "#000080";
            case "darkCyan": return "#008080";
            case "darkGreen": return "#008000";
            case "darkMagenta": return "#800080";
            case "darkRed": return "#800000";
            case "darkYellow": return "#808000";
            case "darkGray": return "#808080";
            case "lightGray": return "#C0C0C0";
            case "black": return "#000000";
            default: return null;
        }
    }

    private void image(String rid, long cx, long cy) {
        String target = rid != null ? rels.get(rid) : null;
        if (target == null || target.startsWith("ext:")) return;
        if (Util.imageMime(target) == null) {
            sb.append("<span style=\"color:#999\">[그림]</span>");
            return;
        }
        sb.append("<img src=\"res/").append(Util.urlPath(target)).append('"');
        if (cx > 0) {
            long w = cx / 9525;
            if (w > 0) sb.append(" style=\"width:").append(w).append("px\"");
        }
        sb.append(" loading=\"lazy\">");
    }

    private void drawing(XNode d) {
        XNode chart = d.find("chart");
        if (chart != null && chart.rel("id") != null) {
            String target = rels.get(chart.rel("id"));
            XNode ext = d.find("extent");
            if (target != null && !target.startsWith("ext:") && ext != null) {
                try {
                    double w = ext.longAttr("cx", 0) / 9525.0, h = ext.longAttr("cy", 0) / 9525.0;
                    String svg = ChartSvg.render(zip.xml(target), w, h, new ChartSvg.Colors() {
                        @Override public String of(XNode holder) {
                            XNode c = holder.child("srgbClr");
                            return c != null ? Util.hexColor(c.attr("val")) : null;
                        }
                        @Override public String series(int i) { return ChartSvg.defaultColor(i); }
                    });
                    if (svg != null) {
                        sb.append("<span style=\"display:block;max-width:100%;width:").append(Math.round(w))
                                .append("px;aspect-ratio:").append(Math.round(w)).append('/').append(Math.max(1, Math.round(h))).append("\">").append(svg).append("</span>");
                        return;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        XNode blip = d.find("blip");
        if (blip != null) {
            XNode ext = d.find("extent");
            image(blip.rel("embed"), ext != null ? ext.longAttr("cx", 0) : 0, ext != null ? ext.longAttr("cy", 0) : 0);
        }
        for (XNode tx : d.findAll("txbxContent")) {
            sb.append("<span style=\"display:block;border:1px solid #ccc;padding:4px;margin:4px 0\">");
            blocks(tx);
            sb.append("</span>");
        }
    }

    private void pict(XNode d) {
        XNode im = d.find("imagedata");
        if (im != null) image(im.rel("id"), 0, 0);
        for (XNode tx : d.findAll("txbxContent")) {
            sb.append("<span style=\"display:block;border:1px solid #ccc;padding:4px;margin:4px 0\">");
            blocks(tx);
            sb.append("</span>");
        }
    }

    // ---------- tables ----------

    private static final class Cell {
        XNode tc;
        int gridStart, span = 1, rowSpan = 1;
        boolean vContinue;
    }

    private void table(XNode tbl) {
        XNode tblPr = tbl.child("tblPr");
        boolean bordered = true;
        if (tblPr != null) {
            XNode borders = tblPr.child("tblBorders");
            if (borders != null) {
                boolean any = false;
                for (XNode b : borders.kids()) {
                    String v = b.attr("val", "");
                    if (!v.equals("none") && !v.equals("nil")) any = true;
                }
                bordered = any;
            } else if (tblPr.child("tblStyle") == null) bordered = false;
        } else bordered = false;
        // Cells that declare borders themselves still get drawn.
        if (!bordered && tbl.find("tcBorders") != null) bordered = true;

        List<Integer> grid = new ArrayList<>();
        XNode tg = tbl.child("tblGrid");
        if (tg != null) for (XNode gc : tg.children("gridCol")) grid.add(gc.intAttr("w", 0));

        List<List<Cell>> rows = new ArrayList<>();
        for (XNode tr : tbl.children("tr")) {
            List<Cell> row = new ArrayList<>();
            int g = 0;
            XNode trPr = tr.child("trPr");
            if (trPr != null) {
                XNode gb = trPr.child("gridBefore");
                if (gb != null) g += gb.intAttr("val", 0);
            }
            List<XNode> tcs = new ArrayList<>();
            for (XNode k : tr.kids()) {
                if (k.name.equals("tc")) tcs.add(k);
                else if (k.name.equals("sdt") && k.child("sdtContent") != null) tcs.addAll(k.child("sdtContent").children("tc"));
            }
            for (XNode tc : tcs) {
                Cell c = new Cell();
                c.tc = tc;
                c.gridStart = g;
                XNode tcPr = tc.child("tcPr");
                if (tcPr != null) {
                    XNode gs = tcPr.child("gridSpan");
                    if (gs != null) c.span = Math.max(1, gs.intAttr("val", 1));
                    XNode vm = tcPr.child("vMerge");
                    if (vm != null && !"restart".equals(vm.attr("val"))) c.vContinue = true;
                }
                g += c.span;
                row.add(c);
            }
            rows.add(row);
        }
        for (int r = 0; r < rows.size(); r++) {
            for (Cell c : rows.get(r)) {
                if (c.vContinue) continue;
                XNode tcPr = c.tc.child("tcPr");
                XNode vm = tcPr != null ? tcPr.child("vMerge") : null;
                if (vm == null) continue;
                for (int rr = r + 1; rr < rows.size(); rr++) {
                    Cell below = null;
                    for (Cell b : rows.get(rr)) if (b.gridStart == c.gridStart) below = b;
                    if (below != null && below.vContinue) c.rowSpan++;
                    else break;
                }
            }
        }
        sb.append("<div class=\"tw\"><table class=\"").append(bordered ? "bd" : "").append('"');
        int total = 0;
        for (int w : grid) total += w;
        if (total > 0) {
            int px = total / 15;
            if (pageWidthPx > 0 && px >= pageWidthPx - 20) sb.append(" style=\"width:100%\"");
            else sb.append(" style=\"width:").append(px).append("px\"");
        }
        sb.append("><tbody>");
        for (List<Cell> row : rows) {
            sb.append("<tr>");
            for (Cell c : row) {
                if (c.vContinue) continue;
                sb.append("<td");
                if (c.span > 1) sb.append(" colspan=\"").append(c.span).append('"');
                if (c.rowSpan > 1) sb.append(" rowspan=\"").append(c.rowSpan).append('"');
                StringBuilder css = new StringBuilder();
                XNode tcPr = c.tc.child("tcPr");
                if (tcPr != null) {
                    XNode shd = tcPr.child("shd");
                    if (shd != null) {
                        String col = Util.hexColor(shd.attr("fill"));
                        if (col != null && !col.equals("#FFFFFF")) css.append("background:").append(col).append(';');
                    }
                    XNode va = tcPr.child("vAlign");
                    if (va != null) {
                        String v = va.attr("val", "");
                        if (v.equals("center")) css.append("vertical-align:middle;");
                        else if (v.equals("bottom")) css.append("vertical-align:bottom;");
                    }
                }
                int w = 0;
                for (int i = c.gridStart; i < c.gridStart + c.span && i < grid.size(); i++) w += grid.get(i);
                if (w > 0 && total > 0) css.append("width:").append(Util.fmt(100.0 * w / total)).append("%;");
                if (css.length() > 0) sb.append(" style=\"").append(css).append('"');
                sb.append('>');
                blocks(c.tc);
                sb.append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</tbody></table></div>");
    }
}
