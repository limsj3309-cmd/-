package com.limsj.docviewer.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Draws DrawingML charts (bar/line/area/pie/scatter) as inline SVG from their cached values. */
public final class ChartSvg {
    public interface Colors {
        /** Resolves a color container (solidFill etc.) to #RRGGBB or null. */
        String of(XNode holder);

        /** Default series color by index. */
        String series(int i);
    }

    private static final String[] PALETTE = {"#4472C4", "#ED7D31", "#A5A5A5", "#FFC000", "#5B9BD5", "#70AD47", "#264478", "#9E480E", "#636363", "#997300"};

    private static final class Series {
        String name, color;
        List<String> cats = new ArrayList<>();
        double[] vals = new double[0];
        List<String> sliceColors = new ArrayList<>();
    }

    private static final class Group {
        String type; // bar, line, area, pie, scatter
        boolean horizontal, stacked, percent;
        List<Series> series = new ArrayList<>();
    }

    private ChartSvg() {}

    public static String render(XNode chartSpace, double w, double h, Colors colors) {
        if (chartSpace == null || w < 20 || h < 20) return null;
        XNode plot = chartSpace.find("plotArea");
        if (plot == null) return null;
        List<Group> groups = new ArrayList<>();
        int serIdx = 0;
        for (XNode g : plot.kids()) {
            String t;
            switch (g.name) {
                case "barChart": case "bar3DChart": t = "bar"; break;
                case "lineChart": case "line3DChart": case "radarChart": case "stockChart": t = "line"; break;
                case "areaChart": case "area3DChart": t = "area"; break;
                case "pieChart": case "pie3DChart": case "doughnutChart": case "ofPieChart": t = "pie"; break;
                case "scatterChart": case "bubbleChart": t = "scatter"; break;
                default: continue;
            }
            Group gr = new Group();
            gr.type = t;
            XNode dir = g.child("barDir");
            gr.horizontal = dir != null && "bar".equals(dir.attr("val"));
            XNode grp = g.child("grouping");
            String gv = grp != null ? grp.attr("val", "") : "";
            gr.stacked = gv.startsWith("stacked") || gv.equals("percentStacked");
            gr.percent = gv.equals("percentStacked");
            for (XNode s : g.children("ser")) {
                Series se = new Series();
                se.name = text(s.child("tx"));
                XNode cat = s.child("cat");
                if (cat == null) cat = s.child("xVal");
                se.cats = strings(cat);
                XNode val = s.child("val");
                if (val == null) val = s.child("yVal");
                se.vals = numbers(val);
                XNode sp = s.child("spPr");
                String c = null;
                if (sp != null) {
                    if (sp.child("solidFill") != null) c = colors.of(sp.child("solidFill"));
                    if (c == null && sp.child("ln") != null && sp.child("ln").child("solidFill") != null) c = colors.of(sp.child("ln").child("solidFill"));
                    if (t.equals("line") && sp.child("ln") != null && sp.child("ln").child("solidFill") != null) c = colors.of(sp.child("ln").child("solidFill"));
                }
                se.color = c != null ? c : seriesColor(colors, serIdx);
                if (t.equals("pie")) {
                    int n = Math.max(se.vals.length, se.cats.size());
                    for (int i = 0; i < n; i++) se.sliceColors.add(seriesColor(colors, i));
                    for (XNode dp : s.children("dPt")) {
                        int idx = dp.child("idx") != null ? dp.child("idx").intAttr("val", -1) : -1;
                        XNode dsp = dp.child("spPr");
                        String dc = dsp != null && dsp.child("solidFill") != null ? colors.of(dsp.child("solidFill")) : null;
                        if (idx >= 0 && idx < n && dc != null) se.sliceColors.set(idx, dc);
                    }
                }
                gr.series.add(se);
                serIdx++;
            }
            if (!gr.series.isEmpty()) groups.add(gr);
        }
        if (groups.isEmpty()) return null;

        String title = null;
        XNode ch = chartSpace.child("chart");
        if (ch == null) ch = chartSpace.find("chart");
        boolean titleDeleted = ch != null && ch.child("autoTitleDeleted") != null && "1".equals(ch.child("autoTitleDeleted").attr("val"));
        XNode tnode = ch != null ? ch.child("title") : null;
        if (tnode != null) {
            StringBuilder tb = new StringBuilder();
            for (XNode t : tnode.findAll("t")) tb.append(t.allText());
            title = tb.length() > 0 ? tb.toString() : null;
            if (title == null && !titleDeleted && groups.get(0).series.size() == 1) title = groups.get(0).series.get(0).name;
        }
        boolean legend = ch != null && ch.child("legend") != null;

        double fs = Math.max(9, Math.min(16, Math.min(w, h) / 20));
        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(f(w)).append(' ').append(f(h))
                .append("\" style=\"width:100%;height:100%;font-family:sans-serif\" font-size=\"").append(f(fs)).append("\">");
        double top = 6, bottom = h - 4;
        if (title != null) {
            sb.append("<text x=\"").append(f(w / 2)).append("\" y=\"").append(f(fs * 1.3)).append("\" text-anchor=\"middle\" font-weight=\"bold\" font-size=\"")
                    .append(f(fs * 1.15)).append("\">").append(Util.esc(title)).append("</text>");
            top += fs * 1.7;
        }
        Group first = groups.get(0);
        List<String[]> legendItems = new ArrayList<>();
        if (first.type.equals("pie")) {
            Series s = first.series.get(0);
            for (int i = 0; i < s.vals.length; i++)
                legendItems.add(new String[]{i < s.cats.size() ? s.cats.get(i) : Integer.toString(i + 1), s.sliceColors.get(i)});
        } else {
            for (Group g : groups) for (Series s : g.series) legendItems.add(new String[]{s.name != null ? s.name : "", s.color});
        }
        if (legend && !legendItems.isEmpty()) {
            double lh = fs * 1.6;
            int perRow = Math.max(1, (int) (w / (fs * 9)));
            int rows = Math.min(3, (legendItems.size() + perRow - 1) / perRow);
            bottom -= rows * lh;
            double colW = w / Math.min(perRow, legendItems.size());
            for (int i = 0; i < legendItems.size() && i < rows * perRow; i++) {
                double x = (i % perRow) * colW + 6, y = bottom + (i / perRow) * lh + lh * 0.75;
                sb.append("<rect x=\"").append(f(x)).append("\" y=\"").append(f(y - fs * 0.75)).append("\" width=\"").append(f(fs * 0.8))
                        .append("\" height=\"").append(f(fs * 0.8)).append("\" fill=\"").append(legendItems.get(i)[1]).append("\"/>");
                sb.append("<text x=\"").append(f(x + fs * 1.1)).append("\" y=\"").append(f(y)).append("\" fill=\"#333\">")
                        .append(Util.esc(clip(legendItems.get(i)[0], (int) (colW / fs * 1.6)))).append("</text>");
            }
            bottom -= 4;
        }
        if (first.type.equals("pie")) pie(sb, first.series.get(0), w, top, bottom, plot.find("doughnutChart") != null);
        else axes(sb, groups, w, top, bottom, fs, chartSpace);
        sb.append("</svg>");
        return sb.toString();
    }

    private static String seriesColor(Colors c, int i) {
        String s = c != null ? c.series(i) : null;
        return s != null ? s : PALETTE[i % PALETTE.length];
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        n = Math.max(3, n);
        return s.length() > n ? s.substring(0, n - 1) + "…" : s;
    }

    private static String f(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }

    private static String text(XNode tx) {
        if (tx == null) return null;
        XNode v = tx.find("v");
        if (v != null) return v.allText();
        StringBuilder sb = new StringBuilder();
        for (XNode t : tx.findAll("t")) sb.append(t.allText());
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static List<String> strings(XNode cat) {
        List<String> out = new ArrayList<>();
        if (cat == null) return out;
        XNode cache = cat.find("strCache");
        if (cache == null) cache = cat.find("numCache");
        if (cache == null) {
            XNode ml = cat.find("multiLvlStrCache");
            if (ml != null) cache = ml.child("lvl");
        }
        if (cache == null) cache = cat.find("strLit");
        if (cache == null) return out;
        int n = cache.child("ptCount") != null ? cache.child("ptCount").intAttr("val", 0) : 0;
        List<XNode> pts = cache.children("pt");
        for (XNode p : pts) n = Math.max(n, p.intAttr("idx", 0) + 1);
        n = Math.min(n, 10000);
        for (int i = 0; i < n; i++) out.add("");
        String fmt = cache.child("formatCode") != null ? cache.child("formatCode").allText() : null;
        for (XNode p : pts) {
            int i = p.intAttr("idx", -1);
            XNode v = p.child("v");
            if (i < 0 || i >= n || v == null) continue;
            String s = v.allText();
            if (fmt != null && NumFmt.isDate(fmt)) {
                try {
                    s = NumFmt.format(Double.parseDouble(s), fmt, false);
                } catch (Exception ignored) {
                }
            }
            out.set(i, s);
        }
        return out;
    }

    private static double[] numbers(XNode val) {
        if (val == null) return new double[0];
        XNode cache = val.find("numCache");
        if (cache == null) cache = val.find("numLit");
        if (cache == null) return new double[0];
        int n = cache.child("ptCount") != null ? cache.child("ptCount").intAttr("val", 0) : 0;
        List<XNode> pts = cache.children("pt");
        for (XNode p : pts) n = Math.max(n, p.intAttr("idx", 0) + 1);
        n = Math.min(n, 10000);
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        for (XNode p : pts) {
            int i = p.intAttr("idx", -1);
            XNode v = p.child("v");
            if (i < 0 || i >= n || v == null) continue;
            try {
                out[i] = Double.parseDouble(v.allText().trim());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static void pie(StringBuilder sb, Series s, double w, double top, double bottom, boolean donut) {
        double total = 0;
        for (double v : s.vals) if (!Double.isNaN(v) && v > 0) total += v;
        if (total <= 0) return;
        double cx = w / 2, cy = (top + bottom) / 2, r = Math.max(5, Math.min(w, bottom - top) / 2 - 6);
        double a = -Math.PI / 2;
        for (int i = 0; i < s.vals.length; i++) {
            double v = s.vals[i];
            if (Double.isNaN(v) || v <= 0) continue;
            double da = v / total * Math.PI * 2;
            String col = s.sliceColors.get(i);
            if (da >= Math.PI * 2 - 1e-6) {
                sb.append("<circle cx=\"").append(f(cx)).append("\" cy=\"").append(f(cy)).append("\" r=\"").append(f(r)).append("\" fill=\"").append(col).append("\"/>");
            } else {
                double x1 = cx + r * Math.cos(a), y1 = cy + r * Math.sin(a);
                double x2 = cx + r * Math.cos(a + da), y2 = cy + r * Math.sin(a + da);
                sb.append("<path d=\"M").append(f(cx)).append(',').append(f(cy)).append(" L").append(f(x1)).append(',').append(f(y1))
                        .append(" A").append(f(r)).append(',').append(f(r)).append(" 0 ").append(da > Math.PI ? 1 : 0).append(" 1 ")
                        .append(f(x2)).append(',').append(f(y2)).append(" Z\" fill=\"").append(col).append("\" stroke=\"#fff\" stroke-width=\"1\"/>");
            }
            double mid = a + da / 2;
            if (da > 0.25) {
                sb.append("<text x=\"").append(f(cx + r * 0.65 * Math.cos(mid))).append("\" y=\"").append(f(cy + r * 0.65 * Math.sin(mid)))
                        .append("\" text-anchor=\"middle\" dominant-baseline=\"middle\" fill=\"#fff\">")
                        .append(Math.round(v / total * 100)).append("%</text>");
            }
            a += da;
        }
        if (donut) sb.append("<circle cx=\"").append(f(cx)).append("\" cy=\"").append(f(cy)).append("\" r=\"").append(f(r * 0.5)).append("\" fill=\"#fff\"/>");
    }

    private static double nice(double x) {
        if (x <= 0 || Double.isNaN(x)) return 1;
        double e = Math.pow(10, Math.floor(Math.log10(x)));
        double m = x / e;
        double n = m <= 1 ? 1 : m <= 2 ? 2 : m <= 2.5 ? 2.5 : m <= 5 ? 5 : 10;
        return n * e;
    }

    private static String label(double v, double step) {
        if (Math.abs(v) < step * 1e-6) v = 0;
        int dec = step >= 1 ? 0 : (int) Math.min(6, Math.ceil(-Math.log10(step)));
        String s = String.format(Locale.ROOT, "%,." + dec + "f", v);
        return s;
    }

    private static void axes(StringBuilder sb, List<Group> groups, double w, double top, double bottom, double fs, XNode chartSpace) {
        int nCat = 0;
        List<String> cats = null;
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        boolean anyBar = false, horizontal = false;
        for (Group g : groups) {
            if (g.type.equals("bar")) {
                anyBar = true;
                horizontal |= g.horizontal;
            }
            int n = 0;
            for (Series s : g.series) n = Math.max(n, Math.max(s.vals.length, s.cats.size()));
            nCat = Math.max(nCat, n);
            for (Series s : g.series) if (cats == null || cats.isEmpty() || (s.cats.size() > cats.size())) cats = s.cats;
            if (g.stacked) {
                for (int i = 0; i < n; i++) {
                    double pos = 0, neg = 0, tot = 0;
                    for (Series s : g.series) {
                        double v = i < s.vals.length && !Double.isNaN(s.vals[i]) ? s.vals[i] : 0;
                        if (v >= 0) pos += v;
                        else neg += v;
                        tot += Math.abs(v);
                    }
                    if (g.percent) { pos = tot > 0 ? 100 : 0; neg = 0; }
                    max = Math.max(max, pos);
                    min = Math.min(min, neg);
                }
            } else {
                for (Series s : g.series) for (double v : s.vals) if (!Double.isNaN(v)) {
                    max = Math.max(max, v);
                    min = Math.min(min, v);
                }
            }
        }
        if (nCat == 0 || min == Double.POSITIVE_INFINITY) return;
        if (anyBar || min > 0 && min < (max - min) * 0.6) min = Math.min(min, 0);
        if (anyBar) max = Math.max(max, 0);
        XNode valAx = chartSpace.find("valAx");
        XNode sc = valAx != null ? valAx.child("scaling") : null;
        Double fixedMin = null, fixedMax = null;
        if (sc != null) {
            try { if (sc.child("min") != null) fixedMin = Double.parseDouble(sc.child("min").attr("val")); } catch (Exception ignored) { }
            try { if (sc.child("max") != null) fixedMax = Double.parseDouble(sc.child("max").attr("val")); } catch (Exception ignored) { }
        }
        if (max == min) { max += 1; min -= min == 0 ? 0 : 1; }
        double step = nice((max - min) / 5);
        double lo = fixedMin != null ? fixedMin : Math.floor(min / step) * step;
        double hi = fixedMax != null ? fixedMax : Math.ceil(max / step) * step;
        if (hi <= lo) hi = lo + step;
        step = nice((hi - lo) / 5);

        // label gutters
        double maxLabel = 0;
        for (double v = lo; v <= hi + step * 1e-6; v += step) maxLabel = Math.max(maxLabel, label(v, step).length());
        double catLabelW = 0;
        if (horizontal && cats != null) for (String c : cats) catLabelW = Math.max(catLabelW, Math.min(12, c.length()));
        double left = horizontal ? Math.min(w * 0.35, catLabelW * fs * 0.75 + 8) : maxLabel * fs * 0.6 + 8;
        double right = w - 8;
        double plotBottom = bottom - (horizontal ? fs * 1.4 : fs * 1.5);
        double plotTop = top + fs * 0.6;
        if (plotBottom - plotTop < 10 || right - left < 10) return;

        // grid + value labels
        for (double v = lo; v <= hi + step * 1e-6; v += step) {
            if (horizontal) {
                double x = left + (v - lo) / (hi - lo) * (right - left);
                sb.append("<line x1=\"").append(f(x)).append("\" y1=\"").append(f(plotTop)).append("\" x2=\"").append(f(x)).append("\" y2=\"").append(f(plotBottom)).append("\" stroke=\"#ddd\"/>");
                sb.append("<text x=\"").append(f(x)).append("\" y=\"").append(f(plotBottom + fs * 1.1)).append("\" text-anchor=\"middle\" fill=\"#666\">").append(label(v, step)).append("</text>");
            } else {
                double y = plotBottom - (v - lo) / (hi - lo) * (plotBottom - plotTop);
                sb.append("<line x1=\"").append(f(left)).append("\" y1=\"").append(f(y)).append("\" x2=\"").append(f(right)).append("\" y2=\"").append(f(y)).append("\" stroke=\"#ddd\"/>");
                sb.append("<text x=\"").append(f(left - 4)).append("\" y=\"").append(f(y + fs * 0.35)).append("\" text-anchor=\"end\" fill=\"#666\">").append(label(v, step)).append("</text>");
            }
        }
        // category labels
        double band = (horizontal ? (plotBottom - plotTop) : (right - left)) / nCat;
        int every = Math.max(1, (int) Math.ceil(nCat / Math.max(1.0, (horizontal ? (plotBottom - plotTop) / (fs * 1.3) : (right - left) / (fs * 3.2)))));
        boolean sparse = cats != null && cats.stream().filter(s -> !s.isEmpty()).count() < nCat;
        if (cats != null) for (int i = 0; i < nCat && i < cats.size(); i++) {
            String c = cats.get(i);
            if (c.isEmpty() || (!sparse && i % every != 0)) continue;
            if (horizontal) {
                double y = plotTop + band * (i + 0.5);
                sb.append("<text x=\"").append(f(left - 4)).append("\" y=\"").append(f(y + fs * 0.35)).append("\" text-anchor=\"end\" fill=\"#444\">").append(Util.esc(clip(c, 14))).append("</text>");
            } else {
                double x = left + band * (sparse ? i : i + 0.5);
                sb.append("<text x=\"").append(f(x)).append("\" y=\"").append(f(plotBottom + fs * 1.2)).append("\" text-anchor=\"").append(sparse ? "start" : "middle").append("\" fill=\"#444\">")
                        .append(Util.esc(clip(c, Math.max(4, (int) (band * every / fs * 1.5))))).append("</text>");
            }
        }
        // zero / base axis
        double zero = Math.max(lo, Math.min(hi, 0));
        if (horizontal) {
            double x = left + (zero - lo) / (hi - lo) * (right - left);
            sb.append("<line x1=\"").append(f(x)).append("\" y1=\"").append(f(plotTop)).append("\" x2=\"").append(f(x)).append("\" y2=\"").append(f(plotBottom)).append("\" stroke=\"#888\"/>");
        } else {
            double y = plotBottom - (zero - lo) / (hi - lo) * (plotBottom - plotTop);
            sb.append("<line x1=\"").append(f(left)).append("\" y1=\"").append(f(y)).append("\" x2=\"").append(f(right)).append("\" y2=\"").append(f(y)).append("\" stroke=\"#888\"/>");
        }

        int barGroups = 0;
        for (Group g : groups) if (g.type.equals("bar")) barGroups += g.stacked ? 1 : g.series.size();
        int barSlot = 0;
        for (Group g : groups) {
            if (g.type.equals("bar")) {
                int slots = g.stacked ? 1 : g.series.size();
                double inner = band * 0.7 / Math.max(1, barGroups);
                double[] pos = new double[nCat], neg = new double[nCat], tot = new double[nCat];
                if (g.percent) for (Series s : g.series) for (int i = 0; i < nCat && i < s.vals.length; i++) if (!Double.isNaN(s.vals[i])) tot[i] += Math.abs(s.vals[i]);
                for (int si = 0; si < g.series.size(); si++) {
                    Series s = g.series.get(si);
                    int slot = barSlot + (g.stacked ? 0 : si);
                    for (int i = 0; i < nCat && i < s.vals.length; i++) {
                        double v = s.vals[i];
                        if (Double.isNaN(v)) continue;
                        if (g.percent) v = tot[i] > 0 ? v / tot[i] * 100 : 0;
                        double a, b;
                        if (g.stacked) {
                            if (v >= 0) { a = pos[i]; pos[i] += v; b = pos[i]; } else { a = neg[i]; neg[i] += v; b = neg[i]; }
                        } else { a = 0; b = v; }
                        double start = band * i + band * 0.15 + inner * slot;
                        if (horizontal) {
                            double x1 = left + (clampV(Math.min(a, b), lo, hi) - lo) / (hi - lo) * (right - left);
                            double x2 = left + (clampV(Math.max(a, b), lo, hi) - lo) / (hi - lo) * (right - left);
                            sb.append("<rect x=\"").append(f(x1)).append("\" y=\"").append(f(plotTop + start)).append("\" width=\"").append(f(Math.max(0, x2 - x1)))
                                    .append("\" height=\"").append(f(inner * 0.92)).append("\" fill=\"").append(s.color).append("\"/>");
                        } else {
                            double y1 = plotBottom - (clampV(Math.max(a, b), lo, hi) - lo) / (hi - lo) * (plotBottom - plotTop);
                            double y2 = plotBottom - (clampV(Math.min(a, b), lo, hi) - lo) / (hi - lo) * (plotBottom - plotTop);
                            sb.append("<rect x=\"").append(f(left + start)).append("\" y=\"").append(f(y1)).append("\" width=\"").append(f(inner * 0.92))
                                    .append("\" height=\"").append(f(Math.max(0, y2 - y1))).append("\" fill=\"").append(s.color).append("\"/>");
                        }
                    }
                }
                barSlot += slots;
            } else {
                double[] acc = new double[nCat];
                for (Series s : g.series) {
                    StringBuilder pts = new StringBuilder();
                    StringBuilder path = new StringBuilder();
                    boolean pen = false;
                    for (int i = 0; i < nCat && i < s.vals.length; i++) {
                        double v = s.vals[i];
                        if (Double.isNaN(v)) { pen = false; continue; }
                        if (g.stacked) { acc[i] += v; v = acc[i]; }
                        double x = left + band * (i + 0.5);
                        double y = plotBottom - (clampV(v, lo, hi) - lo) / (hi - lo) * (plotBottom - plotTop);
                        path.append(pen ? " L" : " M").append(f(x)).append(',').append(f(y));
                        pts.append(f(x)).append(',').append(f(y)).append(' ');
                        pen = true;
                    }
                    if (path.length() == 0) continue;
                    if (g.type.equals("area")) {
                        double y0 = plotBottom - (Math.max(lo, Math.min(hi, 0)) - lo) / (hi - lo) * (plotBottom - plotTop);
                        String[] p = pts.toString().trim().split(" ");
                        if (p.length > 0) {
                            String x0 = p[0].split(",")[0], x1 = p[p.length - 1].split(",")[0];
                            sb.append("<polygon points=\"").append(x0).append(',').append(f(y0)).append(' ').append(pts).append(x1).append(',').append(f(y0))
                                    .append("\" fill=\"").append(s.color).append("\" fill-opacity=\"0.6\"/>");
                        }
                    } else if (g.type.equals("scatter")) {
                        for (String p : pts.toString().trim().split(" ")) {
                            String[] xy = p.split(",");
                            if (xy.length == 2) sb.append("<circle cx=\"").append(xy[0]).append("\" cy=\"").append(xy[1]).append("\" r=\"").append(f(fs * 0.3)).append("\" fill=\"").append(s.color).append("\"/>");
                        }
                    } else {
                        sb.append("<path d=\"").append(path.toString().trim()).append("\" fill=\"none\" stroke=\"").append(s.color)
                                .append("\" stroke-width=\"").append(f(Math.max(1.5, fs * 0.18))).append("\" stroke-linejoin=\"round\"/>");
                    }
                }
            }
        }
    }

    private static double clampV(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static String defaultColor(int i) {
        return PALETTE[i % PALETTE.length];
    }
}
