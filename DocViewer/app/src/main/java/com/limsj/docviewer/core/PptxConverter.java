package com.limsj.docviewer.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** PowerPoint (.pptx) to HTML: each slide becomes an absolutely positioned, scaled box. */
public final class PptxConverter {
    private static final double EMU_PX = 9525.0;

    private final ZipRes zip;
    private final Map<String, Part> parts = new HashMap<>();
    private final StringBuilder sb = new StringBuilder(65536);

    private PptxConverter(ZipRes zip) {
        this.zip = zip;
    }

    public static Doc convert(ZipRes zip) throws Exception {
        return new PptxConverter(zip).run();
    }

    // ---------- parts ----------

    private static final class Part {
        String path;
        XNode xml;
        Map<String, String> rels;
        Part layout, master;
        Map<String, String> colors; // scheme name -> #RRGGBB (master/theme)
        Map<String, String> clrMap;
    }

    private Part part(String path) {
        if (path == null) return null;
        Part p = parts.get(path);
        if (p != null) return p;
        p = new Part();
        p.path = path;
        try {
            p.xml = zip.xml(path);
        } catch (Exception e) {
            p.xml = null;
        }
        p.rels = zip.rels(path);
        parts.put(path, p);
        return p;
    }

    private String relOfType(Part p, String typeSuffix) {
        return zip.relByType(p.path, typeSuffix);
    }

    private Part slidePart(String path) {
        Part s = part(path);
        if (s.xml == null) return s;
        Part layout = part(relOfType(s, "/slideLayout"));
        Part master = layout != null ? part(relOfType(layout, "/slideMaster")) : null;
        s.layout = layout;
        s.master = master;
        if (master != null && master.colors == null) loadTheme(master);
        if (master != null) {
            s.colors = master.colors;
            s.clrMap = master.clrMap;
        }
        if (layout != null) {
            layout.master = master;
            layout.colors = s.colors;
            layout.clrMap = s.clrMap;
        }
        if (s.colors == null) {
            s.colors = new HashMap<>();
            s.clrMap = new HashMap<>();
        }
        return s;
    }

    private void loadTheme(Part master) {
        master.colors = new HashMap<>();
        master.clrMap = new HashMap<>();
        if (master.xml != null) {
            XNode cm = master.xml.find("clrMap");
            if (cm != null) for (String k : new String[]{"bg1", "tx1", "bg2", "tx2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink"}) {
                String v = cm.attr(k);
                if (v != null) master.clrMap.put(k, v);
            }
        }
        try {
            XNode th = zip.xml(relOfType(master, "/theme"));
            XNode cs = th != null ? th.find("clrScheme") : null;
            if (cs != null) for (XNode c : cs.kids()) {
                XNode s = c.child("srgbClr");
                String v = null;
                if (s != null) v = Util.hexColor(s.attr("val"));
                XNode sys = c.child("sysClr");
                if (v == null && sys != null) v = Util.hexColor(sys.attr("lastClr"));
                if (v != null) master.colors.put(c.name, v);
            }
        } catch (Exception ignored) {
        }
        if (master.colors.isEmpty()) {
            master.colors.put("dk1", "#000000");
            master.colors.put("lt1", "#FFFFFF");
            master.colors.put("dk2", "#44546A");
            master.colors.put("lt2", "#E7E6E6");
            master.colors.put("accent1", "#4472C4");
        }
    }

    // ---------- colors ----------

    private String scheme(Part p, String name) {
        if (name == null) return null;
        String mapped = name;
        if (p.clrMap != null && p.clrMap.containsKey(name)) mapped = p.clrMap.get(name);
        else if (name.equals("tx1")) mapped = "dk1";
        else if (name.equals("bg1")) mapped = "lt1";
        else if (name.equals("tx2")) mapped = "dk2";
        else if (name.equals("bg2")) mapped = "lt2";
        if (p.colors == null) return null;
        String c = p.colors.get(mapped);
        return c != null ? c : p.colors.get(name);
    }

    /** Resolves a color element container (e.g. solidFill, fontRef). */
    private String color(Part p, XNode holder, String phClr) {
        if (holder == null) return null;
        for (XNode c : holder.kids()) {
            String base = null;
            switch (c.name) {
                case "srgbClr": base = Util.hexColor(c.attr("val")); break;
                case "schemeClr":
                    base = "phClr".equals(c.attr("val")) ? phClr : scheme(p, c.attr("val"));
                    break;
                case "sysClr": base = Util.hexColor(c.attr("lastClr")); if (base == null) base = "window".equals(c.attr("val")) ? "#FFFFFF" : "#000000"; break;
                case "prstClr": base = preset(c.attr("val")); break;
                case "scrgbClr": base = "#808080"; break;
                default: continue;
            }
            if (base == null) return null;
            return mods(base, c);
        }
        return null;
    }

    private static String preset(String v) {
        if (v == null) return null;
        switch (v) {
            case "black": return "#000000";
            case "white": return "#FFFFFF";
            case "red": return "#FF0000";
            case "green": return "#008000";
            case "blue": return "#0000FF";
            case "yellow": return "#FFFF00";
            case "gray": case "grey": return "#808080";
            case "orange": return "#FFA500";
            default: return "#808080";
        }
    }

    private static String mods(String hex, XNode c) {
        if (c.kids().isEmpty()) return hex;
        int r = Integer.parseInt(hex.substring(1, 3), 16), g = Integer.parseInt(hex.substring(3, 5), 16), b = Integer.parseInt(hex.substring(5, 7), 16);
        double[] hsl = toHsl(r, g, b);
        boolean hslChanged = false;
        for (XNode m : c.kids()) {
            double v = m.intAttr("val", 100000) / 100000.0;
            switch (m.name) {
                case "lumMod": hsl[2] *= v; hslChanged = true; break;
                case "lumOff": hsl[2] += v; hslChanged = true; break;
                case "satMod": hsl[1] *= v; hslChanged = true; break;
                case "tint": {
                    if (hslChanged) { int[] rgb = fromHsl(hsl); r = rgb[0]; g = rgb[1]; b = rgb[2]; hslChanged = false; }
                    r = (int) (r + (255 - r) * (1 - v));
                    g = (int) (g + (255 - g) * (1 - v));
                    b = (int) (b + (255 - b) * (1 - v));
                    hsl = toHsl(r, g, b);
                    break;
                }
                case "shade": {
                    if (hslChanged) { int[] rgb = fromHsl(hsl); r = rgb[0]; g = rgb[1]; b = rgb[2]; hslChanged = false; }
                    r = (int) (r * v);
                    g = (int) (g * v);
                    b = (int) (b * v);
                    hsl = toHsl(r, g, b);
                    break;
                }
                default: break;
            }
        }
        hsl[1] = Math.max(0, Math.min(1, hsl[1]));
        hsl[2] = Math.max(0, Math.min(1, hsl[2]));
        int[] rgb = fromHsl(hsl);
        return String.format(Locale.ROOT, "#%02X%02X%02X", rgb[0], rgb[1], rgb[2]);
    }

    private static double[] toHsl(int r, int g, int b) {
        double rf = r / 255.0, gf = g / 255.0, bf = b / 255.0;
        double max = Math.max(rf, Math.max(gf, bf)), min = Math.min(rf, Math.min(gf, bf));
        double h = 0, s, l = (max + min) / 2;
        if (max == min) s = 0;
        else {
            double d = max - min;
            s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
            if (max == rf) h = (gf - bf) / d + (gf < bf ? 6 : 0);
            else if (max == gf) h = (bf - rf) / d + 2;
            else h = (rf - gf) / d + 4;
            h /= 6;
        }
        return new double[]{h, s, l};
    }

    private static int[] fromHsl(double[] hsl) {
        double h = hsl[0], s = hsl[1], l = hsl[2];
        double r, g, b;
        if (s == 0) r = g = b = l;
        else {
            double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
            double p = 2 * l - q;
            r = hue(p, q, h + 1.0 / 3);
            g = hue(p, q, h);
            b = hue(p, q, h - 1.0 / 3);
        }
        return new int[]{clamp(r * 255), clamp(g * 255), clamp(b * 255)};
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    private static double hue(double p, double q, double t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1.0 / 6) return p + (q - p) * 6 * t;
        if (t < 0.5) return q;
        if (t < 2.0 / 3) return p + (q - p) * (2.0 / 3 - t) * 6;
        return p;
    }

    // ---------- main ----------

    private Doc run() throws Exception {
        String presPath = zip.relByType("", "/officeDocument");
        if (presPath == null || !zip.has(presPath)) presPath = "ppt/presentation.xml";
        XNode pres = zip.xml(presPath);
        if (pres == null) throw new Doc.UserError("프레젠테이션을 찾을 수 없습니다.");
        long cx = 9144000, cy = 6858000;
        XNode sz = pres.find("sldSz");
        if (sz != null) {
            cx = sz.longAttr("cx", cx);
            cy = sz.longAttr("cy", cy);
        }
        int w = (int) Math.round(cx / EMU_PX), h = (int) Math.round(cy / EMU_PX);
        Map<String, String> rels = zip.rels(presPath);
        List<String> slides = new ArrayList<>();
        XNode lst = pres.find("sldIdLst");
        if (lst != null) for (XNode s : lst.children("sldId")) {
            String t = rels.get(s.rel("id"));
            if (t != null && zip.has(t)) slides.add(t);
        }
        int n = slides.size();
        for (int i = 0; i < n; i++) {
            sb.append("<div class=\"sw\" style=\"width:").append(w).append("px;height:").append(h).append("px\">");
            int mark = sb.length();
            try {
                slide(slides.get(i), w, h);
            } catch (Exception e) {
                sb.setLength(mark);
                sb.append("<div class=\"sl\" style=\"width:").append(w).append("px;height:").append(h)
                        .append("px;background:#fff;display:flex;align-items:center;justify-content:center;color:#888;font-size:28px\">이 슬라이드를 표시할 수 없습니다</div>");
            }
            sb.append("</div><div class=\"sn\">").append(i + 1).append(" / ").append(n).append("</div>");
        }
        if (n == 0) return new Doc(Html.message("빈 프레젠테이션", "표시할 슬라이드가 없습니다."), zip, zip);
        String css = "body{background:#3a3b3f}"
                + ".sw{margin:8px auto 0;overflow:hidden;background:#fff;box-shadow:0 2px 8px rgba(0,0,0,.5)}"
                + ".sl{position:relative;overflow:hidden;transform-origin:0 0;background:#fff;color:#000;font-family:sans-serif;line-height:1.2;word-wrap:break-word;overflow-wrap:break-word}"
                + ".sp{position:absolute;display:flex;flex-direction:column}"
                + ".sp>.tx{overflow:visible}"
                + ".sl p{margin:0}"
                + ".sn{text-align:center;color:#bbb;font-size:12px;margin:4px 0 6px;font-family:sans-serif}"
                + ".sl table{position:absolute;border-collapse:collapse;table-layout:fixed}"
                + ".sl td{vertical-align:top;padding:4.8px 9.6px;overflow:hidden}"
                + ".sl svg{position:absolute;overflow:visible}";
        // Slides keep their real pixel size; the viewport is set to the slide width so the
        // WebView opens fitted to the screen and pinch zoom re-renders text sharply.
        // Android fonts can be wider than the originals; text that no longer fits its table or
        // box is shrunk (down to 60%) so it doesn't spill over the next element.
        String js = "function shrink(el,box,limit){var s=el.querySelectorAll('span'),b=[];for(var i=0;i<s.length;i++)b.push(parseFloat(s[i].style.fontSize)||0);"
                + "for(var k=0.95;k>=0.6&&box.offsetHeight>limit+1;k-=0.05)for(var i=0;i<s.length;i++)if(b[i])s[i].style.fontSize=(b[i]*k)+'pt';}"
                + "var t=document.querySelectorAll('.sl table[data-h]');for(var i=0;i<t.length;i++){var h=+t[i].getAttribute('data-h');if(h>0)shrink(t[i],t[i],h);}"
                + "var x=document.querySelectorAll('.sp>.tx');for(var i=0;i<x.length;i++){var p=x[i].parentNode,cs=getComputedStyle(p),"
                + "lim=p.clientHeight-parseFloat(cs.paddingTop)-parseFloat(cs.paddingBottom);if(lim>4)shrink(x[i],x[i],lim);}";
        return new Doc(Html.page("ppt", css, sb.toString(), js, w + 16), zip, zip);
    }

    private void slide(String path, int w, int h) {
        Part s = slidePart(path);
        if (s.xml == null) throw new IllegalStateException("bad slide");
        String bg = background(s);
        if (bg == null && s.layout != null && s.layout.xml != null) bg = background(s.layout);
        if (bg == null && s.master != null && s.master.xml != null) bg = background(s.master);
        sb.append("<div class=\"sl\" style=\"width:").append(w).append("px;height:").append(h).append("px;");
        if (bg != null) sb.append(bg);
        sb.append("\">");
        Xf id = new Xf();
        // Master / layout decorations (non-placeholder shapes), unless the slide hides them.
        XNode sld = s.xml.find("sld");
        boolean showMaster = sld == null || !"0".equals(sld.attr("showMasterSp"));
        if (showMaster && s.layout != null && s.layout.xml != null) {
            XNode lsld = s.layout.xml.find("sldLayout");
            boolean layoutShowsMaster = lsld == null || !"0".equals(lsld.attr("showMasterSp"));
            if (layoutShowsMaster && s.master != null && s.master.xml != null) {
                XNode tree = s.master.xml.find("spTree");
                if (tree != null) shapes(tree, s.master, s, id, true);
            }
            XNode tree = s.layout.xml.find("spTree");
            if (tree != null) shapes(tree, s.layout, s, id, true);
        }
        XNode tree = s.xml.find("spTree");
        if (tree != null) shapes(tree, s, s, id, false);
        sb.append("</div>");
    }

    private String background(Part p) {
        XNode bg = p.xml.find("bg");
        if (bg == null) return null;
        XNode pr = bg.child("bgPr");
        if (pr != null) {
            XNode blip = pr.find("blip");
            if (blip != null) {
                String t = p.rels.get(blip.rel("embed"));
                if (t != null && !t.startsWith("ext:") && Util.imageMime(t) != null)
                    return "background:#fff url('res/" + Util.urlPath(t) + "') center/100% 100% no-repeat;";
            }
            String c = fill(p, pr, null);
            if (c != null) return "background:" + c + ";";
        }
        XNode ref = bg.child("bgRef");
        if (ref != null) {
            String c = color(p, ref, null);
            if (c != null) return "background:" + c + ";";
        }
        return null;
    }

    /** CSS background value for a spPr-like node, or null if none / "transparent" for noFill. */
    private String fill(Part p, XNode spPr, String phClr) {
        if (spPr == null) return null;
        if (spPr.child("noFill") != null) return "transparent";
        XNode sf = spPr.child("solidFill");
        if (sf != null) return color(p, sf, phClr);
        XNode gf = spPr.child("gradFill");
        if (gf != null) {
            List<XNode> gs = gf.findAll("gs");
            if (gs.size() >= 2) {
                String a = color(p, gs.get(0), phClr), b = color(p, gs.get(gs.size() - 1), phClr);
                if (a != null && b != null) {
                    XNode lin = gf.child("lin");
                    int ang = lin != null ? lin.intAttr("ang", 0) / 60000 : 90;
                    return "linear-gradient(" + (ang + 90) + "deg," + a + "," + b + ")";
                }
            }
            if (!gs.isEmpty()) return color(p, gs.get(0), phClr);
        }
        return null;
    }

    // ---------- geometry ----------

    private static final class Xf {
        double sx = 1, sy = 1, ox = 0, oy = 0;

        double x(double v) { return ox + v * sx; }
        double y(double v) { return oy + v * sy; }
    }

    private static final class Box {
        double x, y, w, h, rot;
        boolean flipH, flipV;
    }

    private Box box(XNode xfrm, Xf t) {
        if (xfrm == null) return null;
        XNode off = xfrm.child("off"), ext = xfrm.child("ext");
        if (off == null || ext == null) return null;
        Box b = new Box();
        double x = off.longAttr("x", 0), y = off.longAttr("y", 0);
        double cx = ext.longAttr("cx", 0), cy = ext.longAttr("cy", 0);
        b.x = t.x(x) / EMU_PX;
        b.y = t.y(y) / EMU_PX;
        b.w = cx * t.sx / EMU_PX;
        b.h = cy * t.sy / EMU_PX;
        b.rot = xfrm.intAttr("rot", 0) / 60000.0;
        b.flipH = "1".equals(xfrm.attr("flipH"));
        b.flipV = "1".equals(xfrm.attr("flipV"));
        return b;
    }

    private void pos(Box b) {
        sb.append("left:").append(Util.fmt(b.x)).append("px;top:").append(Util.fmt(b.y))
                .append("px;width:").append(Util.fmt(Math.max(b.w, 0))).append("px;height:").append(Util.fmt(Math.max(b.h, 0))).append("px;");
        if (b.rot != 0) sb.append("transform:rotate(").append(Util.fmt(b.rot)).append("deg);");
    }

    // ---------- shapes ----------

    private static final class Ph {
        String type, idx;
    }

    private static Ph ph(XNode shape) {
        XNode nv = null;
        for (XNode k : shape.kids()) if (k.name.startsWith("nv")) nv = k;
        if (nv == null) return null;
        XNode nvPr = nv.child("nvPr");
        XNode ph = nvPr != null ? nvPr.child("ph") : null;
        if (ph == null) return null;
        Ph p = new Ph();
        p.type = ph.attr("type", "obj");
        p.idx = ph.attr("idx");
        return p;
    }

    private static String phClass(String type) {
        if (type == null) return "body";
        switch (type) {
            case "title": case "ctrTitle": return "title";
            case "body": case "subTitle": case "obj": return "body";
            default: return type;
        }
    }

    /** Finds the matching placeholder shape in a layout/master. */
    private static XNode findPh(Part p, Ph ph) {
        if (p == null || p.xml == null || ph == null) return null;
        XNode tree = p.xml.find("spTree");
        if (tree == null) return null;
        XNode byType = null;
        for (XNode sp : tree.findAll("sp")) {
            Ph o = ph(sp);
            if (o == null) continue;
            if (ph.idx != null && ph.idx.equals(o.idx)) {
                // index match is authoritative unless types clearly differ (title vs body)
                if (phClass(o.type).equals(phClass(ph.type)) || "obj".equals(ph.type) || "obj".equals(o.type)) return sp;
            }
            if (byType == null && o.type.equals(ph.type)) byType = sp;
        }
        if (byType != null) return byType;
        for (XNode sp : tree.findAll("sp")) {
            Ph o = ph(sp);
            if (o != null && phClass(o.type).equals(phClass(ph.type))) return sp;
        }
        return null;
    }

    private void shapes(XNode tree, Part src, Part slide, Xf t, boolean inherited) {
        for (XNode n : tree.kids()) {
            try {
                switch (n.name) {
                    case "sp": shape(n, src, slide, t, inherited); break;
                    case "pic": picture(n, src, t, inherited); break;
                    case "grpSp": group(n, src, slide, t, inherited); break;
                    case "graphicFrame": frame(n, src, slide, t); break;
                    case "cxnSp": line(n, src, t); break;
                    default: break;
                }
            } catch (RuntimeException ignored) {
                // skip a broken shape, keep the rest of the slide
            }
        }
    }

    private void group(XNode g, Part src, Part slide, Xf t, boolean inherited) {
        XNode gp = g.child("grpSpPr");
        XNode xfrm = gp != null ? gp.child("xfrm") : null;
        Xf nt = new Xf();
        nt.sx = t.sx; nt.sy = t.sy; nt.ox = t.ox; nt.oy = t.oy;
        if (xfrm != null) {
            XNode off = xfrm.child("off"), ext = xfrm.child("ext"), cho = xfrm.child("chOff"), che = xfrm.child("chExt");
            if (off != null && ext != null && cho != null && che != null) {
                double ecx = ext.longAttr("cx", 1), ecy = ext.longAttr("cy", 1);
                double ccx = che.longAttr("cx", 0), ccy = che.longAttr("cy", 0);
                double kx = ccx > 0 ? ecx / ccx : 1, ky = ccy > 0 ? ecy / ccy : 1;
                nt.sx = t.sx * kx;
                nt.sy = t.sy * ky;
                nt.ox = t.ox + t.sx * (off.longAttr("x", 0) - cho.longAttr("x", 0) * kx);
                nt.oy = t.oy + t.sy * (off.longAttr("y", 0) - cho.longAttr("y", 0) * ky);
            }
        }
        shapes(g, src, slide, nt, inherited);
    }

    private void shape(XNode sp, Part src, Part slide, Xf t, boolean inherited) {
        Ph ph = ph(sp);
        if (inherited && ph != null) return; // layout/master placeholders are prompts, not content
        XNode spPr = sp.child("spPr");
        XNode layoutSp = null, masterSp = null;
        if (ph != null && !inherited) {
            layoutSp = findPh(slide.layout, ph);
            masterSp = findPh(slide.master, ph);
        }
        Box b = box(spPr != null ? spPr.child("xfrm") : null, t);
        if (b == null && layoutSp != null) b = box(layoutSp.path("spPr", "xfrm"), t);
        if (b == null && masterSp != null) b = box(masterSp.path("spPr", "xfrm"), t);
        if (b == null) return;

        XNode style = sp.child("style");
        String geom = null;
        XNode pg = spPr != null ? spPr.child("prstGeom") : null;
        if (pg != null) geom = pg.attr("prst");
        if ("line".equals(geom) || "straightConnector1".equals(geom)) {
            drawLine(b, spPr, style, src);
            return;
        }

        String bg = fill(src, spPr, null);
        if (bg == null && spPr != null && spPr.child("blipFill") != null) {
            XNode blip = spPr.child("blipFill").find("blip");
            String tgt = blip != null ? src.rels.get(blip.rel("embed")) : null;
            if (tgt != null && Util.imageMime(tgt) != null) bg = "url('res/" + Util.urlPath(tgt) + "') center/100% 100% no-repeat";
        }
        if (bg == null && style != null) {
            XNode fr = style.child("fillRef");
            if (fr != null && fr.intAttr("idx", 0) > 0) bg = color(src, fr, null);
        }
        if (bg == null && ph == null && spPr == null) bg = null;
        String border = line(src, spPr, style);

        sb.append("<div class=\"sp\" style=\"");
        pos(b);
        if (bg != null && !"transparent".equals(bg)) sb.append("background:").append(bg).append(';');
        if (border != null) sb.append("border:").append(border).append(';');
        if ("ellipse".equals(geom) || "flowChartConnector".equals(geom)) sb.append("border-radius:50%;");
        else if (geom != null && geom.startsWith("round")) sb.append("border-radius:").append(Util.fmt(Math.min(b.w, b.h) * 0.16)).append("px;");

        XNode tx = sp.child("txBody");
        if (tx == null || tx.allText().trim().isEmpty() && tx.findAll("br").isEmpty()) {
            sb.append("\"></div>");
            return;
        }
        XNode bodyPr = tx.child("bodyPr");
        XNode lBody = layoutSp != null ? layoutSp.path("txBody", "bodyPr") : null;
        XNode mBody = masterSp != null ? masterSp.path("txBody", "bodyPr") : null;
        String anchor = firstAttr("anchor", bodyPr, lBody, mBody);
        if (anchor == null) anchor = ph != null && "title".equals(phClass(ph.type)) && !"ctrTitle".equals(ph.type) ? "ctr" : "t";
        if ("ctrTitle".equals(ph != null ? ph.type : null) && firstAttr("anchor", bodyPr, lBody, mBody) == null) anchor = "b";
        sb.append("justify-content:").append(anchor.equals("ctr") ? "center" : anchor.equals("b") ? "flex-end" : "flex-start").append(';');
        double l = ins(firstAttr("lIns", bodyPr, lBody, mBody), 91440), r = ins(firstAttr("rIns", bodyPr, lBody, mBody), 91440);
        double tp = ins(firstAttr("tIns", bodyPr, lBody, mBody), 45720), bt = ins(firstAttr("bIns", bodyPr, lBody, mBody), 45720);
        sb.append("padding:").append(Util.fmt(tp)).append("px ").append(Util.fmt(r)).append("px ").append(Util.fmt(bt)).append("px ").append(Util.fmt(l)).append("px;");
        String wrap = firstAttr("wrap", bodyPr, lBody, mBody);
        if ("none".equals(wrap)) sb.append("white-space:nowrap;");
        sb.append("\"><div class=\"tx\">");

        double scale = 1;
        XNode fit = bodyPr != null ? bodyPr.child("normAutofit") : null;
        if (fit != null) scale = fit.intAttr("fontScale", 100000) / 100000.0;

        List<XNode> lists = new ArrayList<>();
        addList(lists, tx.child("lstStyle"));
        if (layoutSp != null) addList(lists, layoutSp.path("txBody", "lstStyle"));
        if (masterSp != null) addList(lists, masterSp.path("txBody", "lstStyle"));
        XNode master = slide.master != null && slide.master.xml != null ? slide.master.xml.find("txStyles") : null;
        if (src != slide && src.xml != null && master == null) master = src.xml.find("txStyles");
        if (master != null) {
            String cls = ph != null ? phClass(ph.type) : "other";
            if (cls.equals("title")) addList(lists, master.child("titleStyle"));
            else if (cls.equals("body")) addList(lists, master.child("bodyStyle"));
            else addList(lists, master.child("otherStyle"));
        }
        String fontColor = null;
        if (style != null && style.child("fontRef") != null) fontColor = color(src, style.child("fontRef"), null);
        paragraphs(tx, lists, src, scale, fontColor);
        sb.append("</div></div>");
    }

    private static void addList(List<XNode> lists, XNode n) {
        if (n != null) lists.add(n);
    }

    private static String firstAttr(String a, XNode... nodes) {
        for (XNode n : nodes) if (n != null && n.attr(a) != null) return n.attr(a);
        return null;
    }

    private static double ins(String v, long def) {
        long e = def;
        if (v != null) {
            try {
                e = Long.parseLong(v);
            } catch (Exception ignored) {
            }
        }
        return e / EMU_PX;
    }

    private String line(Part src, XNode spPr, XNode style) {
        XNode ln = spPr != null ? spPr.child("ln") : null;
        if (ln != null && ln.child("noFill") != null) return null;
        String c = ln != null && ln.child("solidFill") != null ? color(src, ln.child("solidFill"), null) : null;
        if (c == null && style != null && style.child("lnRef") != null && style.child("lnRef").intAttr("idx", 0) > 0 && (ln == null || ln.child("solidFill") == null)) {
            c = color(src, style.child("lnRef"), null);
        }
        if (c == null) return null;
        double w = ln != null ? ln.longAttr("w", 12700) / EMU_PX : 1;
        if (w < 0.75) w = 0.75;
        String dash = "solid";
        XNode pd = ln != null ? ln.child("prstDash") : null;
        if (pd != null && !"solid".equals(pd.attr("val"))) dash = pd.attr("val", "").contains("dot") ? "dotted" : "dashed";
        return Util.fmt(w) + "px " + dash + " " + c;
    }

    private void drawLine(Box b, XNode spPr, XNode style, Part src) {
        String c = null;
        XNode ln = spPr != null ? spPr.child("ln") : null;
        if (ln != null && ln.child("noFill") != null) return;
        if (ln != null && ln.child("solidFill") != null) c = color(src, ln.child("solidFill"), null);
        if (c == null && style != null && style.child("lnRef") != null) c = color(src, style.child("lnRef"), null);
        if (c == null) c = "#000000";
        double w = ln != null ? Math.max(0.75, ln.longAttr("w", 12700) / EMU_PX) : 1;
        double x1 = 0, y1 = 0, x2 = b.w, y2 = b.h;
        if (b.flipH) { x1 = b.w; x2 = 0; }
        if (b.flipV) { y1 = b.h; y2 = 0; }
        sb.append("<svg style=\"left:").append(Util.fmt(b.x)).append("px;top:").append(Util.fmt(b.y)).append("px;width:")
                .append(Util.fmt(Math.max(b.w, 1))).append("px;height:").append(Util.fmt(Math.max(b.h, 1))).append("px;");
        if (b.rot != 0) sb.append("transform:rotate(").append(Util.fmt(b.rot)).append("deg);");
        sb.append("\"><line x1=\"").append(Util.fmt(x1)).append("\" y1=\"").append(Util.fmt(y1)).append("\" x2=\"").append(Util.fmt(x2))
                .append("\" y2=\"").append(Util.fmt(y2)).append("\" stroke=\"").append(c).append("\" stroke-width=\"").append(Util.fmt(w)).append("\"/></svg>");
    }

    private void line(XNode cxn, Part src, Xf t) {
        XNode spPr = cxn.child("spPr");
        Box b = box(spPr != null ? spPr.child("xfrm") : null, t);
        if (b == null) return;
        drawLine(b, spPr, cxn.child("style"), src);
    }

    private void picture(XNode pic, Part src, Xf t, boolean inherited) {
        if (inherited && ph(pic) != null) return;
        XNode spPr = pic.child("spPr");
        Box b = box(spPr != null ? spPr.child("xfrm") : null, t);
        if (b == null) return;
        XNode bf = pic.child("blipFill");
        XNode blip = bf != null ? bf.child("blip") : null;
        String tgt = blip != null ? src.rels.get(blip.rel("embed")) : null;
        sb.append("<div class=\"sp\" style=\"overflow:hidden;");
        pos(b);
        if (b.flipH || b.flipV) sb.append("transform:scale(").append(b.flipH ? -1 : 1).append(',').append(b.flipV ? -1 : 1).append(");");
        sb.append("\">");
        if (tgt != null && !tgt.startsWith("ext:") && Util.imageMime(tgt) != null) {
            XNode src2 = bf.child("srcRect");
            double l = 0, tp = 0, r = 0, bt = 0;
            if (src2 != null) {
                l = src2.intAttr("l", 0) / 100000.0;
                tp = src2.intAttr("t", 0) / 100000.0;
                r = src2.intAttr("r", 0) / 100000.0;
                bt = src2.intAttr("b", 0) / 100000.0;
            }
            double fw = 1 - l - r, fh = 1 - tp - bt;
            if (fw <= 0.01 || fh <= 0.01) { l = tp = 0; fw = fh = 1; }
            double iw = b.w / fw, ih = b.h / fh;
            sb.append("<img loading=\"lazy\" src=\"res/").append(Util.urlPath(tgt)).append("\" style=\"position:absolute;max-width:none;left:")
                    .append(Util.fmt(-l * iw)).append("px;top:").append(Util.fmt(-tp * ih)).append("px;width:").append(Util.fmt(iw))
                    .append("px;height:").append(Util.fmt(ih)).append("px\">");
        } else {
            sb.append("<div style=\"width:100%;height:100%;background:#eee;border:1px dashed #bbb\"></div>");
        }
        sb.append("</div>");
    }

    private void frame(XNode gf, Part src, Part slide, Xf t) {
        Box b = box(gf.child("xfrm"), t);
        if (b == null) return;
        XNode gd = gf.find("graphicData");
        String uri = gd != null ? gd.attr("uri", "") : "";
        XNode tbl = gd != null ? gd.child("tbl") : null;
        if (tbl != null) {
            table(tbl, b, src);
            return;
        }
        if (uri.contains("diagram") && gd != null) {
            if (smartArt(gd, src, slide, b)) return;
        }
        XNode pic = gd != null ? gd.find("pic") : null;
        if (pic != null) {
            XNode blip = pic.find("blip");
            String tgt = blip != null ? src.rels.get(blip.rel("embed")) : null;
            if (tgt != null && Util.imageMime(tgt) != null) {
                sb.append("<div class=\"sp\" style=\"");
                pos(b);
                sb.append("\"><img loading=\"lazy\" src=\"res/").append(Util.urlPath(tgt)).append("\" style=\"width:100%;height:100%\"></div>");
                return;
            }
        }
        if (uri.contains("chart") && gd != null && chart(gd, src, b)) return;
        String label = uri.contains("chart") ? "차트" : uri.contains("diagram") ? "SmartArt" : "개체";
        sb.append("<div class=\"sp\" style=\"");
        pos(b);
        sb.append("background:#f3f4f6;border:1px dashed #b5b8c0;align-items:center;justify-content:center;color:#888;font-size:20px\">")
                .append(label).append("</div>");
    }

    private boolean chart(XNode gd, Part src, Box b) {
        XNode c = gd.child("chart");
        String path = c != null ? src.rels.get(c.rel("id")) : null;
        if (path == null || path.startsWith("ext:")) return false;
        try {
            XNode cx = zip.xml(path);
            String svg = ChartSvg.render(cx, b.w, b.h, new ChartSvg.Colors() {
                @Override public String of(XNode holder) { return color(src, holder, null); }
                @Override public String series(int i) { return scheme(src, "accent" + (i % 6 + 1)); }
            });
            if (svg == null) return false;
            sb.append("<div class=\"sp\" style=\"");
            pos(b);
            sb.append("\">").append(svg).append("</div>");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean smartArt(XNode gd, Part src, Part slide, Box b) {
        XNode ids = gd.child("relIds");
        if (ids == null) return false;
        String dm = src.rels.get(ids.rel("dm"));
        String drawing = null;
        try {
            XNode data = dm != null ? zip.xml(dm) : null;
            XNode ext = data != null ? data.find("dataModelExt") : null;
            if (ext != null) drawing = src.rels.get(ext.rel("relId"));
        } catch (Exception ignored) {
        }
        if (drawing == null) {
            for (String v : src.rels.values()) if (v != null && v.contains("diagrams/drawing")) drawing = v;
        }
        if (drawing == null || !zip.has(drawing)) return false;
        Part dp = part(drawing);
        if (dp.xml == null) return false;
        dp.colors = slide.colors;
        dp.clrMap = slide.clrMap;
        XNode tree = dp.xml.find("spTree");
        if (tree == null) return false;
        Xf t = new Xf();
        t.ox = b.x * EMU_PX;
        t.oy = b.y * EMU_PX;
        shapes(tree, dp, slide, t, false);
        return true;
    }

    private void table(XNode tbl, Box b, Part src) {
        List<Double> cols = new ArrayList<>();
        XNode grid = tbl.child("tblGrid");
        if (grid != null) for (XNode gc : grid.children("gridCol")) cols.add(gc.longAttr("w", 0) / EMU_PX);
        XNode tp = tbl.child("tblPr");
        boolean firstRow = tp != null && "1".equals(tp.attr("firstRow"));
        boolean band = tp != null && "1".equals(tp.attr("bandRow"));
        boolean styled = tp != null && tp.child("tableStyleId") != null;
        String accent = scheme(src, "accent1");
        if (accent == null) accent = "#4472C4";
        double tableH = 0;
        for (XNode tr : tbl.children("tr")) tableH += tr.longAttr("h", 0) / EMU_PX;
        sb.append("<table data-h=\"").append(Util.fmt(tableH)).append("\" style=\"left:").append(Util.fmt(b.x)).append("px;top:").append(Util.fmt(b.y)).append("px;width:").append(Util.fmt(b.w)).append("px\"><colgroup>");
        for (double c : cols) sb.append("<col style=\"width:").append(Util.fmt(c)).append("px\">");
        sb.append("</colgroup>");
        int r = 0;
        for (XNode tr : tbl.children("tr")) {
            sb.append("<tr style=\"height:").append(Util.fmt(tr.longAttr("h", 0) / EMU_PX)).append("px\">");
            for (XNode tc : tr.children("tc")) {
                if ("1".equals(tc.attr("hMerge")) || "1".equals(tc.attr("vMerge"))) continue;
                sb.append("<td");
                int gs = tc.intAttr("gridSpan", 1), rs = tc.intAttr("rowSpan", 1);
                if (gs > 1) sb.append(" colspan=\"").append(gs).append('"');
                if (rs > 1) sb.append(" rowspan=\"").append(rs).append('"');
                StringBuilder css = new StringBuilder();
                XNode tcPr = tc.child("tcPr");
                String bg = tcPr != null ? fill(src, tcPr, null) : null;
                String fc = null;
                if (bg == null && styled) {
                    if (firstRow && r == 0) {
                        bg = accent;
                        fc = "#FFFFFF";
                    } else if (band) bg = (r % 2 == 1) ? mix(accent, 0.2) : mix(accent, 0.1);
                }
                if (bg != null && !"transparent".equals(bg)) css.append("background:").append(bg).append(';');
                css.append("border:1px solid ").append(styled ? "#FFFFFF" : "#888").append(';');
                if (tcPr != null) {
                    css.append("padding:").append(Util.fmt(tcPr.longAttr("marT", 45720) / EMU_PX)).append("px ")
                            .append(Util.fmt(tcPr.longAttr("marR", 91440) / EMU_PX)).append("px ")
                            .append(Util.fmt(tcPr.longAttr("marB", 45720) / EMU_PX)).append("px ")
                            .append(Util.fmt(tcPr.longAttr("marL", 91440) / EMU_PX)).append("px;");
                    String an = tcPr.attr("anchor");
                    if ("ctr".equals(an)) css.append("vertical-align:middle;");
                    else if ("b".equals(an)) css.append("vertical-align:bottom;");
                }
                if (fc != null) css.append("color:").append(fc).append(";font-weight:bold;");
                sb.append(" style=\"").append(css).append("\">");
                XNode tx = tc.child("txBody");
                if (tx != null) {
                    List<XNode> lists = new ArrayList<>();
                    addList(lists, tx.child("lstStyle"));
                    paragraphs(tx, lists, src, 1, fc);
                }
                sb.append("</td>");
            }
            sb.append("</tr>");
            r++;
        }
        sb.append("</table>");
    }

    private static String mix(String hex, double amount) {
        int r = Integer.parseInt(hex.substring(1, 3), 16), g = Integer.parseInt(hex.substring(3, 5), 16), b = Integer.parseInt(hex.substring(5, 7), 16);
        r = (int) (255 - (255 - r) * amount);
        g = (int) (255 - (255 - g) * amount);
        b = (int) (255 - (255 - b) * amount);
        return String.format(Locale.ROOT, "#%02X%02X%02X", r, g, b);
    }

    // ---------- text ----------

    private static XNode lvl(List<XNode> lists, int lvl, String child) {
        String name = "lvl" + (lvl + 1) + "pPr";
        for (XNode l : lists) {
            XNode p = l.child(name);
            if (p == null) continue;
            if (child == null) return p;
            XNode c = p.child(child);
            if (c != null) return c;
        }
        return null;
    }

    private static String lvlAttr(List<XNode> lists, int lvl, String attr) {
        String name = "lvl" + (lvl + 1) + "pPr";
        for (XNode l : lists) {
            XNode p = l.child(name);
            if (p != null && p.attr(attr) != null) return p.attr(attr);
        }
        return null;
    }

    private static String rprAttr(List<XNode> lists, int lvl, String attr) {
        String name = "lvl" + (lvl + 1) + "pPr";
        for (XNode l : lists) {
            XNode p = l.child(name);
            XNode d = p != null ? p.child("defRPr") : null;
            if (d != null && d.attr(attr) != null) return d.attr(attr);
        }
        return null;
    }

    private String rprColor(List<XNode> lists, int lvl, Part src) {
        String name = "lvl" + (lvl + 1) + "pPr";
        for (XNode l : lists) {
            XNode p = l.child(name);
            XNode d = p != null ? p.child("defRPr") : null;
            if (d != null && d.child("solidFill") != null) return color(src, d.child("solidFill"), null);
        }
        return null;
    }

    private void paragraphs(XNode tx, List<XNode> lists, Part src, double scale, String fontColor) {
        int[] autoNum = new int[9];
        for (XNode p : tx.children("p")) {
            XNode pPr = p.child("pPr");
            int lvl = pPr != null ? Math.max(0, Math.min(8, pPr.intAttr("lvl", 0))) : 0;
            String algn = pPr != null && pPr.attr("algn") != null ? pPr.attr("algn") : lvlAttr(lists, lvl, "algn");
            String marL = pPr != null && pPr.attr("marL") != null ? pPr.attr("marL") : lvlAttr(lists, lvl, "marL");
            String indent = pPr != null && pPr.attr("indent") != null ? pPr.attr("indent") : lvlAttr(lists, lvl, "indent");

            // bullet
            String bullet = null;
            XNode bsrc = null;
            if (pPr != null && (pPr.child("buNone") != null || pPr.child("buChar") != null || pPr.child("buAutoNum") != null)) bsrc = pPr;
            if (bsrc == null) {
                String name = "lvl" + (lvl + 1) + "pPr";
                for (XNode l : lists) {
                    XNode lp = l.child(name);
                    if (lp != null && (lp.child("buNone") != null || lp.child("buChar") != null || lp.child("buAutoNum") != null)) {
                        bsrc = lp;
                        break;
                    }
                }
            }
            boolean hasText = !p.allText().trim().isEmpty();
            if (bsrc != null && hasText) {
                if (bsrc.child("buChar") != null) {
                    bullet = bsrc.child("buChar").attr("char", "•");
                    if (bullet.isEmpty()) bullet = null;
                    else {
                        char ch = bullet.charAt(0);
                        if (ch >= 0xE000 && ch <= 0xF8FF || ch == '§' || ch == 'Ø' || ch == 'ü' || ch == 'q' || ch == 'v' || ch == 'Ø' || ch == 'n' || ch == 'l') {
                            XNode font = bsrc.child("buFont");
                            String tf = font != null ? font.attr("typeface", "") : "";
                            if (tf.toLowerCase(Locale.ROOT).contains("wingdings") || tf.toLowerCase(Locale.ROOT).contains("symbol") || ch >= 0xE000)
                                bullet = ch == 'ü' ? "✓" : ch == 'Ø' || ch == 'Ø' ? "➢" : ch == 'q' || ch == 'n' ? "▪" : "•";
                        }
                    }
                } else if (bsrc.child("buAutoNum") != null) {
                    autoNum[lvl]++;
                    String type = bsrc.child("buAutoNum").attr("type", "arabicPeriod");
                    int start = bsrc.child("buAutoNum").intAttr("startAt", 1);
                    int n = autoNum[lvl] + start - 1;
                    String num;
                    if (type.startsWith("alphaLc")) num = NumText.letters(n, 'a');
                    else if (type.startsWith("alphaUc")) num = NumText.letters(n, 'A');
                    else if (type.startsWith("romanLc")) num = NumText.roman(n).toLowerCase(Locale.ROOT);
                    else if (type.startsWith("romanUc")) num = NumText.roman(n);
                    else if (type.startsWith("circleNum")) num = NumText.format(n, "decimalEnclosedCircle");
                    else if (type.startsWith("hangul")) num = NumText.cycle(n, "가나다라마바사아자차카타파하");
                    else num = Integer.toString(n);
                    if (type.endsWith("ParenBoth")) num = "(" + num + ")";
                    else if (type.endsWith("ParenR")) num = num + ")";
                    else if (type.endsWith("Period")) num = num + ".";
                    bullet = num;
                }
            }
            if (bullet == null) for (int i = lvl; i < 9; i++) if (bsrc == null || bsrc.child("buAutoNum") == null) autoNum[i] = 0;

            sb.append("<p style=\"");
            if (algn != null) {
                switch (algn) {
                    case "ctr": sb.append("text-align:center;"); break;
                    case "r": sb.append("text-align:right;"); break;
                    case "just": case "dist": sb.append("text-align:justify;"); break;
                    default: break;
                }
            }
            double ml = marL != null ? Util.parseInt(marL, 0) / EMU_PX : 0;
            double ind = indent != null ? Util.parseInt(indent, 0) / EMU_PX : 0;
            if (ml != 0) sb.append("padding-left:").append(Util.fmt(ml)).append("px;");
            if (ind != 0 && bullet != null) sb.append("text-indent:").append(Util.fmt(ind)).append("px;");
            XNode lnSpc = pPr != null ? pPr.child("lnSpc") : null;
            if (lnSpc == null) lnSpc = lvl(lists, lvl, "lnSpc");
            if (lnSpc != null && lnSpc.child("spcPct") != null) {
                double v = lnSpc.child("spcPct").intAttr("val", 100000) / 100000.0;
                sb.append("line-height:").append(Util.fmt(Math.max(0.6, v * 1.2))).append(';');
            }
            XNode spcBef = pPr != null ? pPr.child("spcBef") : null;
            if (spcBef == null) spcBef = lvl(lists, lvl, "spcBef");
            if (spcBef != null && spcBef.child("spcPts") != null) {
                double v = spcBef.child("spcPts").intAttr("val", 0) / 100.0;
                if (v > 0) sb.append("margin-top:").append(Util.fmt(v * scale)).append("pt;");
            }
            sb.append("\">");

            String baseSz = rprAttr(lists, lvl, "sz");
            String baseB = rprAttr(lists, lvl, "b");
            String baseI = rprAttr(lists, lvl, "i");
            String baseColor = fontColor != null ? fontColor : rprColor(lists, lvl, src);
            if (bullet != null) {
                double s = (baseSz != null ? Util.parseInt(baseSz, 1800) : 1800) / 100.0 * scale;
                XNode firstR = p.child("r");
                if (firstR != null && firstR.child("rPr") != null && firstR.child("rPr").attr("sz") != null)
                    s = firstR.child("rPr").intAttr("sz", 1800) / 100.0 * scale;
                sb.append("<span style=\"font-size:").append(Util.fmt(s)).append("pt;");
                String bc = baseColor;
                if (bsrc != null && bsrc.child("buClr") != null) bc = color(src, bsrc.child("buClr"), null);
                if (bc != null) sb.append("color:").append(bc).append(';');
                sb.append("\">");
                Util.escTo(sb, bullet);
                sb.append("&nbsp;</span>");
            }
            boolean any = false;
            for (XNode r : p.kids()) {
                if (r.name.equals("r") || r.name.equals("fld")) {
                    any = true;
                    run(r, baseSz, baseB, baseI, baseColor, src, scale);
                } else if (r.name.equals("br")) {
                    sb.append("<br>");
                    any = true;
                }
            }
            if (!any) {
                XNode end = p.child("endParaRPr");
                double s = (end != null && end.attr("sz") != null ? end.intAttr("sz", 1800) : Util.parseInt(baseSz, 1800)) / 100.0 * scale;
                sb.append("<span style=\"font-size:").append(Util.fmt(s)).append("pt\">&nbsp;</span>");
            }
            sb.append("</p>");
        }
    }

    private void run(XNode r, String baseSz, String baseB, String baseI, String baseColor, Part src, double scale) {
        XNode rPr = r.child("rPr");
        String szs = rPr != null && rPr.attr("sz") != null ? rPr.attr("sz") : baseSz;
        double sz = (szs != null ? Util.parseInt(szs, 1800) : 1800) / 100.0 * scale;
        String b = rPr != null && rPr.attr("b") != null ? rPr.attr("b") : baseB;
        String i = rPr != null && rPr.attr("i") != null ? rPr.attr("i") : baseI;
        String col = null;
        if (rPr != null && rPr.child("solidFill") != null) col = color(src, rPr.child("solidFill"), null);
        if (col == null) col = baseColor;
        sb.append("<span style=\"font-size:").append(Util.fmt(sz)).append("pt;");
        if ("1".equals(b) || "true".equals(b)) sb.append("font-weight:bold;");
        if ("1".equals(i) || "true".equals(i)) sb.append("font-style:italic;");
        if (rPr != null) {
            String u = rPr.attr("u");
            String strike = rPr.attr("strike");
            String deco = "";
            if (u != null && !"none".equals(u)) deco += " underline";
            if (strike != null && !"noStrike".equals(strike)) deco += " line-through";
            if (!deco.isEmpty()) sb.append("text-decoration:").append(deco.trim()).append(';');
            String base = rPr.attr("baseline");
            if (base != null) {
                int bl = Util.parseInt(base, 0);
                if (bl > 0) sb.append("vertical-align:super;font-size:").append(Util.fmt(sz * 0.7)).append("pt;");
                else if (bl < 0) sb.append("vertical-align:sub;font-size:").append(Util.fmt(sz * 0.7)).append("pt;");
            }
            XNode hl = rPr.child("highlight");
            if (hl != null) {
                String hc = color(src, hl, null);
                if (hc != null) sb.append("background:").append(hc).append(';');
            }
        }
        if (col != null) sb.append("color:").append(col).append(';');
        sb.append("\">");
        XNode t = r.child("t");
        String s = t != null ? t.allText() : "";
        if (rPr != null && "all".equals(rPr.attr("cap"))) s = s.toUpperCase(Locale.ROOT);
        String e = Util.esc(s);
        if (e.contains("  ")) e = e.replace("  ", " &nbsp;");
        if (e.indexOf('\t') >= 0) e = e.replace("\t", "&emsp;");
        sb.append(e);
        sb.append("</span>");
    }
}
