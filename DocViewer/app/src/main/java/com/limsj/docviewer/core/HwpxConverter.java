package com.limsj.docviewer.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Hangul (.hwpx, OWPML) to HTML. */
public final class HwpxConverter {
    private final ZipRes zip;
    private final Map<String, String> charCss = new HashMap<>();
    private final Map<String, String> paraAlign = new HashMap<>();
    private final Map<String, String> binItems = new HashMap<>();
    private final StringBuilder sb = new StringBuilder(65536);

    private HwpxConverter(ZipRes zip) {
        this.zip = zip;
    }

    public static Doc convert(ZipRes zip) throws Exception {
        return new HwpxConverter(zip).run();
    }

    private Doc run() throws Exception {
        List<String> sections = new ArrayList<>();
        String hpf = "Contents/content.hpf";
        try {
            XNode container = zip.xml("META-INF/container.xml");
            if (container != null) {
                for (XNode rf : container.findAll("rootfile")) {
                    String p = rf.attr("full-path");
                    if (p != null && p.endsWith(".hpf") && zip.has(p)) hpf = p;
                }
            }
        } catch (Exception ignored) {
        }
        String hpfDir = Util.dirOf(hpf);
        String header = null;
        try {
            XNode opf = zip.xml(hpf);
            if (opf != null) {
                Map<String, String> items = new HashMap<>();
                for (XNode it : opf.findAll("item")) {
                    String id = it.attr("id"), href = it.attr("href");
                    if (id == null || href == null) continue;
                    String path = zip.has(href) ? href : Util.resolve(hpfDir, href);
                    items.put(id, path);
                    binItems.put(id, path);
                    if (href.toLowerCase(java.util.Locale.ROOT).endsWith("header.xml")) header = path;
                }
                for (XNode ref : opf.findAll("itemref")) {
                    String p = items.get(ref.attr("idref"));
                    if (p != null && p.toLowerCase(java.util.Locale.ROOT).contains("section")) sections.add(p);
                }
            }
        } catch (Exception ignored) {
        }
        if (sections.isEmpty()) {
            Pattern pat = Pattern.compile("(?i)contents/section(\\d+)\\.xml");
            Map<Integer, String> names = new HashMap<>();
            for (String n : zip.names()) {
                Matcher m = pat.matcher(n);
                if (m.matches()) {
                    int k = Integer.parseInt(m.group(1));
                    names.put(k, n);
                }
            }
            List<Integer> keys = new ArrayList<>(names.keySet());
            Collections.sort(keys);
            for (int k : keys) sections.add(names.get(k));
        }
        if (sections.isEmpty()) throw new Doc.UserError("한글 문서 본문을 찾을 수 없습니다.");
        if (header == null) header = "Contents/header.xml";
        loadHeader(header);

        int pwMark = sb.length();
        sb.append("<div class=\"page\" data-pw=\"605\">");
        boolean pwSet = false;
        for (int i = 0; i < sections.size(); i++) {
            if (i > 0) sb.append("<hr class=\"pb\">");
            XNode sec = zip.xml(sections.get(i));
            if (sec == null) continue;
            if (!pwSet) {
                XNode pp = sec.find("pagePr");
                if (pp != null) {
                    long w = "NARROWLY".equals(pp.attr("landscape")) ? pp.longAttr("height", 0) : pp.longAttr("width", 0);
                    XNode mg = pp.child("margin");
                    if (mg != null) w -= mg.longAttr("left", 0) + mg.longAttr("right", 0);
                    long px = w / 75;
                    if (px > 100 && px < 4000) {
                        String tag = "<div class=\"page\" data-pw=\"605\">";
                        sb.replace(pwMark, pwMark + tag.length(), "<div class=\"page\" data-pw=\"" + px + "\">");
                    }
                }
                pwSet = true;
            }
            XNode root = sec.child("sec");
            blocks(root != null ? root : sec);
        }
        sb.append("</div>");
        return new Doc(Html.page("hwp", null, sb.toString(), null), zip, zip);
    }

    private void loadHeader(String path) {
        try {
            XNode h = zip.xml(path);
            if (h == null) return;
            for (XNode cp : h.findAll("charPr")) {
                StringBuilder c = new StringBuilder();
                int height = cp.intAttr("height", 1000);
                if (height > 0 && height != 1000 && height < 40000) c.append("font-size:").append(Util.fmt(height / 100.0)).append("pt;");
                String col = Util.hexColor(cp.attr("textColor"));
                if (col != null && !col.equals("#000000")) c.append("color:").append(col).append(';');
                String shade = Util.hexColor(cp.attr("shadeColor"));
                if (shade != null && !shade.equals("#FFFFFF")) c.append("background:").append(shade).append(';');
                if (cp.child("bold") != null) c.append("font-weight:bold;");
                if (cp.child("italic") != null) c.append("font-style:italic;");
                String deco = "";
                XNode u = cp.child("underline");
                if (u != null && !"NONE".equalsIgnoreCase(u.attr("type", "NONE"))) deco += " underline";
                XNode s = cp.child("strikeout");
                if (s != null && !"NONE".equalsIgnoreCase(s.attr("shape", "NONE")) && !"3D".equals(s.attr("shape"))) deco += " line-through";
                if (!deco.isEmpty()) c.append("text-decoration:").append(deco.trim()).append(';');
                if (cp.child("supscript") != null) c.append("vertical-align:super;font-size:smaller;");
                if (cp.child("subscript") != null) c.append("vertical-align:sub;font-size:smaller;");
                charCss.put(cp.attr("id"), c.toString());
            }
            for (XNode pp : h.findAll("paraPr")) {
                XNode al = pp.find("align");
                if (al == null) continue;
                String a = al.attr("horizontal", "");
                String css = null;
                switch (a) {
                    case "CENTER": css = "center"; break;
                    case "RIGHT": css = "right"; break;
                    case "JUSTIFY": case "DISTRIBUTE": case "DISTRIBUTE_SPACE": css = "justify"; break;
                    default: break;
                }
                if (css != null) paraAlign.put(pp.attr("id"), css);
            }
        } catch (Exception ignored) {
        }
    }

    private void blocks(XNode parent) {
        for (XNode n : parent.kids()) {
            if (n.name.equals("p")) paragraph(n);
            else if (n.name.equals("tbl")) table(n);
        }
    }

    private void paragraph(XNode p) {
        String align = paraAlign.get(p.attr("paraPrIDRef"));
        open(align);
        int mark = sb.length();
        boolean[] opened = {true};
        for (XNode run : p.kids()) {
            if (!run.name.equals("run")) continue;
            String css = charCss.get(run.attr("charPrIDRef"));
            for (XNode k : run.kids()) {
                switch (k.name) {
                    case "t": text(k, css); break;
                    case "tbl":
                        closeP(opened, mark);
                        table(k);
                        open(align);
                        mark = sb.length();
                        opened[0] = true;
                        break;
                    case "pic": picture(k); break;
                    case "ctrl": case "secPr": case "header": case "footer": case "footNote": case "endNote":
                    case "fieldBegin": case "fieldEnd": case "bookmark": case "pageNum": case "colPr":
                        break;
                    case "equation": {
                        XNode sc = k.child("script");
                        if (sc != null) {
                            sb.append("<i>");
                            Util.escTo(sb, sc.allText());
                            sb.append("</i>");
                        }
                        break;
                    }
                    default: {
                        // drawing objects (rect, ellipse, container, ...) may hold text boxes or pictures
                        List<XNode> subs = k.findAll("subList");
                        List<XNode> pics = k.findAll("pic");
                        if (!subs.isEmpty() || !pics.isEmpty()) {
                            closeP(opened, mark);
                            for (XNode pic : pics) {
                                sb.append("<p>");
                                picture(pic);
                                sb.append("</p>");
                            }
                            if (!subs.isEmpty()) {
                                sb.append("<div style=\"border:1px solid #ccc;padding:4px 6px;margin:4px 0\">");
                                for (XNode s : subs) blocks(s);
                                sb.append("</div>");
                            }
                            open(align);
                            mark = sb.length();
                            opened[0] = true;
                        }
                        break;
                    }
                }
            }
        }
        closeP(opened, mark);
    }

    private void open(String align) {
        sb.append("<p");
        if (align != null) sb.append(" style=\"text-align:").append(align).append('"');
        sb.append('>');
    }

    private void closeP(boolean[] opened, int mark) {
        if (!opened[0]) return;
        if (sb.length() == mark) sb.append("<br>");
        sb.append("</p>");
        opened[0] = false;
    }

    private void text(XNode t, String css) {
        boolean span = css != null && !css.isEmpty();
        if (span) sb.append("<span style=\"").append(css).append("\">");
        for (XNode k : t.kids()) {
            if (k.isText()) {
                String e = Util.esc(k.text);
                if (e.contains("  ")) e = e.replace("  ", " &nbsp;");
                sb.append(e);
            } else switch (k.name) {
                case "tab": sb.append("&emsp;"); break;
                case "lineBreak": sb.append("<br>"); break;
                case "nbSpace": case "fwSpace": sb.append("&nbsp;"); break;
                case "hyphen": sb.append('-'); break;
                default:
                    Util.escTo(sb, k.allText());
                    break;
            }
        }
        if (span) sb.append("</span>");
    }

    private void picture(XNode pic) {
        XNode img = pic.find("img");
        if (img == null) return;
        String path = binItems.get(img.attr("binaryItemIDRef"));
        if (path == null) {
            String id = img.attr("binaryItemIDRef");
            for (String n : zip.names()) if (id != null && n.startsWith("BinData/" + id + ".")) path = n;
        }
        if (path == null || Util.imageMime(path) == null) {
            sb.append("<span style=\"color:#999\">[그림]</span>");
            return;
        }
        long w = 0;
        XNode sz = pic.child("curSz");
        if (sz != null) w = sz.longAttr("width", 0);
        if (w <= 0 && pic.child("sz") != null) w = pic.child("sz").longAttr("width", 0);
        sb.append("<img loading=\"lazy\" src=\"res/").append(Util.urlPath(path)).append('"');
        if (w > 0) sb.append(" style=\"width:").append(w / 75).append("px\"");
        sb.append('>');
    }

    private void table(XNode tbl) {
        long total = 0;
        List<XNode> rows = tbl.children("tr");
        if (!rows.isEmpty()) for (XNode tc : rows.get(0).children("tc")) {
            XNode sz = tc.child("cellSz");
            if (sz != null) total += Math.max(0, sz.longAttr("width", 0));
        }
        sb.append("<div class=\"tw\"><table class=\"bd\" style=\"width:100%\"><tbody>");
        for (XNode tr : rows) {
            sb.append("<tr>");
            for (XNode tc : tr.children("tc")) {
                sb.append("<td");
                XNode span = tc.child("cellSpan");
                if (span != null) {
                    int cs = span.intAttr("colSpan", 1), rs = span.intAttr("rowSpan", 1);
                    if (cs > 1) sb.append(" colspan=\"").append(cs).append('"');
                    if (rs > 1) sb.append(" rowspan=\"").append(rs).append('"');
                }
                XNode sz = tc.child("cellSz");
                StringBuilder css = new StringBuilder();
                if (sz != null) {
                    long w = sz.longAttr("width", 0);
                    if (w > 0 && total > 0) css.append("width:").append(Util.fmt(Math.min(100.0, 100.0 * w / total))).append("%;");
                }
                XNode sub = tc.child("subList");
                if (sub != null) {
                    String va = sub.attr("vertAlign", "");
                    if (va.equals("CENTER")) css.append("vertical-align:middle;");
                    else if (va.equals("BOTTOM")) css.append("vertical-align:bottom;");
                }
                if (css.length() > 0) sb.append(" style=\"").append(css).append('"');
                sb.append('>');
                if (sub != null) blocks(sub);
                sb.append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</tbody></table></div>");
    }
}
