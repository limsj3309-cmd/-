package com.limsj.docviewer.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Spreadsheet model + HTML rendering shared by XLSX and XLS. */
public final class Sheets {
    public static final int MAX_ROWS = 5000;
    public static final int MAX_COLS = 256;
    public static final int MAX_CELLS = 400_000;

    public static final class Cell {
        public String text;
        public boolean number;
        public String css; // extra inline css or null
    }

    public static final class Sheet {
        public String name;
        public final TreeMap<Integer, TreeMap<Integer, Cell>> rows = new TreeMap<>();
        public final Map<Integer, Integer> colWidthPx = new HashMap<>();
        public final List<int[]> merges = new ArrayList<>(); // r1,c1,r2,c2 (0-based)
        public boolean truncated;
        public int cellCount;

        /** Returns false when limits are hit. */
        public boolean put(int r, int c, Cell cell) {
            if (r >= MAX_ROWS || c >= MAX_COLS || cellCount >= MAX_CELLS) {
                truncated = true;
                return false;
            }
            TreeMap<Integer, Cell> row = rows.get(r);
            if (row == null) {
                row = new TreeMap<>();
                rows.put(r, row);
            }
            if (row.put(c, cell) == null) cellCount++;
            return true;
        }
    }

    public static String colName(int c) {
        StringBuilder sb = new StringBuilder();
        c++;
        while (c > 0) {
            int m = (c - 1) % 26;
            sb.insert(0, (char) ('A' + m));
            c = (c - 1) / 26;
        }
        return sb.toString();
    }

    /** "AB12" -> {row 11, col 27}; returns null when unparsable. */
    public static int[] ref(String r) {
        if (r == null) return null;
        int i = 0, col = 0;
        while (i < r.length() && Character.isLetter(r.charAt(i))) {
            col = col * 26 + (Character.toUpperCase(r.charAt(i)) - 'A' + 1);
            i++;
        }
        if (i == 0 || i == r.length()) return null;
        int row = Util.parseInt(r.substring(i).replace("$", ""), -1);
        if (row < 1) return null;
        return new int[]{row - 1, col - 1};
    }

    private static final String CSS =
            "body{background:#fff}"
            + ".tabs{position:sticky;top:0;z-index:5;display:flex;overflow-x:auto;background:#f1f3f4;border-bottom:1px solid #ccc;white-space:nowrap}"
            + ".tabs button{border:0;background:none;padding:10px 14px;font-size:14px;color:#333;flex:none}"
            + ".tabs button.on{background:#fff;color:#107c41;font-weight:bold;border-bottom:3px solid #107c41}"
            + ".sh{display:none}.sh.on{display:block}"
            + ".grid{overflow:auto;-webkit-overflow-scrolling:touch}"
            + "table.x{border-collapse:collapse;font-size:13px;font-family:sans-serif}"
            + "table.x td{border:1px solid #dadce0;padding:2px 4px;white-space:nowrap;height:21px;vertical-align:bottom;overflow:hidden}"
            + "table.x td.n{text-align:right}"
            + "table.x th{background:#f1f3f4;color:#555;font-weight:normal;border:1px solid #c8c8c8;padding:2px 4px;font-size:12px;position:sticky;top:0}"
            + "table.x th.rh{position:sticky;left:0;z-index:2;text-align:center;min-width:34px}"
            + "table.x tr:first-child th.rh{z-index:3}"
            + ".empty{padding:30px;color:#888;text-align:center}";

    private static final String JS =
            "function sel(i){var s=document.querySelectorAll('.sh'),b=document.querySelectorAll('.tabs button');"
            + "for(var k=0;k<s.length;k++){s[k].className=k==i?'sh on':'sh';b[k].className=k==i?'on':'';}window.scrollTo(0,0);}";

    public static String render(List<Sheet> sheets) {
        StringBuilder sb = new StringBuilder(65536);
        if (sheets.isEmpty()) return Html.message("빈 통합 문서", "표시할 시트가 없습니다.");
        sb.append("<div class=\"tabs\">");
        for (int i = 0; i < sheets.size(); i++) {
            sb.append("<button onclick=\"sel(").append(i).append(")\"").append(i == 0 ? " class=\"on\"" : "").append('>');
            Util.escTo(sb, sheets.get(i).name);
            sb.append("</button>");
        }
        sb.append("</div>");
        for (int i = 0; i < sheets.size(); i++) {
            Sheet s = sheets.get(i);
            sb.append("<div class=\"sh").append(i == 0 ? " on" : "").append("\">");
            if (s.truncated) {
                sb.append("<div class=\"note\" style=\"margin:8px\">시트가 커서 앞부분(최대 ")
                        .append(MAX_ROWS).append("행)만 표시합니다.</div>");
            }
            renderSheet(sb, s);
            sb.append("</div>");
        }
        return Html.page("xls", CSS, sb.toString(), JS);
    }

    private static void renderSheet(StringBuilder sb, Sheet s) {
        int maxRow = -1, maxCol = -1;
        for (Map.Entry<Integer, TreeMap<Integer, Cell>> e : s.rows.entrySet()) {
            for (Map.Entry<Integer, Cell> ce : e.getValue().entrySet()) {
                Cell c = ce.getValue();
                if ((c.text != null && !c.text.isEmpty()) || c.css != null && c.css.contains("background")) {
                    maxRow = Math.max(maxRow, e.getKey());
                    maxCol = Math.max(maxCol, ce.getKey());
                }
            }
        }
        for (int[] m : s.merges) {
            if (m[0] <= maxRow + 1 && m[1] <= maxCol + 1) {
                maxRow = Math.max(maxRow, Math.min(m[2], MAX_ROWS - 1));
                maxCol = Math.max(maxCol, Math.min(m[3], MAX_COLS - 1));
            }
        }
        if (maxRow < 0) {
            sb.append("<div class=\"empty\">빈 시트</div>");
            return;
        }
        // merge maps
        Map<Long, int[]> mergeStart = new HashMap<>();
        java.util.HashSet<Long> covered = new java.util.HashSet<>();
        for (int[] m : s.merges) {
            int r2 = Math.min(m[2], maxRow), c2 = Math.min(m[3], maxCol);
            if (m[0] > r2 || m[1] > c2) continue;
            if ((long) (r2 - m[0] + 1) * (c2 - m[1] + 1) > 200000) continue;
            mergeStart.put(key(m[0], m[1]), new int[]{r2 - m[0] + 1, c2 - m[1] + 1});
            for (int r = m[0]; r <= r2; r++)
                for (int c = m[1]; c <= c2; c++)
                    if (r != m[0] || c != m[1]) covered.add(key(r, c));
        }
        sb.append("<div class=\"grid\"><table class=\"x\"><colgroup><col>");
        for (int c = 0; c <= maxCol; c++) {
            Integer w = s.colWidthPx.get(c);
            int px = w != null ? w : 72;
            sb.append("<col style=\"width:").append(px).append("px\">");
        }
        sb.append("</colgroup><tr><th class=\"rh\"></th>");
        for (int c = 0; c <= maxCol; c++) {
            Integer w = s.colWidthPx.get(c);
            int px = w != null ? w : 72;
            sb.append("<th style=\"min-width:").append(px).append("px").append(px == 0 ? ";display:none" : "").append("\">").append(colName(c)).append("</th>");
        }
        sb.append("</tr>");
        for (int r = 0; r <= maxRow; r++) {
            TreeMap<Integer, Cell> row = s.rows.get(r);
            sb.append("<tr><th class=\"rh\">").append(r + 1).append("</th>");
            for (int c = 0; c <= maxCol; c++) {
                long k = key(r, c);
                if (covered.contains(k)) continue;
                Cell cell = row != null ? row.get(c) : null;
                sb.append("<td");
                int[] span = mergeStart.get(k);
                if (span != null) {
                    if (span[0] > 1) sb.append(" rowspan=\"").append(span[0]).append('"');
                    if (span[1] > 1) sb.append(" colspan=\"").append(span[1]).append('"');
                }
                if (cell != null) {
                    if (cell.number) sb.append(" class=\"n\"");
                    if (cell.css != null) sb.append(" style=\"").append(cell.css).append('"');
                    sb.append('>');
                    if (cell.text != null) {
                        String t = Util.esc(cell.text);
                        if (t.indexOf('\n') >= 0) t = t.replace("\n", "<br>");
                        sb.append(t);
                    }
                } else sb.append('>');
                sb.append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</table></div>");
    }

    private static long key(int r, int c) {
        return ((long) r << 20) | c;
    }
}
