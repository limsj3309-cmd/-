package com.limsj.docviewer.core;

/** Page shell shared by all converters. */
public final class Html {
    private Html() {}

    private static final String BASE_CSS =
            "*{box-sizing:border-box}"
            + "html{-webkit-text-size-adjust:none}"
            + "body{margin:0;background:#e8e9ed;color:#111;font-family:sans-serif;word-wrap:break-word;overflow-wrap:anywhere}"
            + "img{max-width:100%;height:auto}"
            + ".page{background:#fff;max-width:900px;margin:10px auto;padding:22px 18px;box-shadow:0 1px 4px rgba(0,0,0,.18);line-height:1.5}"
            + ".page p{margin:0 0 .25em;min-height:1em}"
            + ".tw{overflow-x:auto;margin:6px 0;-webkit-overflow-scrolling:touch}"
            + ".page table{border-collapse:collapse;max-width:none}"
            + ".page td,.page th{vertical-align:top;padding:3px 5px}"
            + ".bd>tbody>tr>td{border:1px solid #888}"
            + ".page td p{margin:0}"
            + ".page td{min-width:2.6em;word-break:keep-all}"
            + "hr.pb{border:0;border-top:2px dashed #c5c7cf;margin:18px -18px}"
            + ".note{font-size:13px;color:#555;background:#fff8d6;border:1px solid #ecd98a;border-radius:8px;padding:8px 12px;margin:10px auto;max-width:900px}"
            + ".fn{font-size:.85em;color:#333;border-top:1px solid #ccc;margin-top:16px;padding-top:6px}"
            + ".tb{white-space:pre-wrap}";

    public static String page(String bodyClass, String extraCss, String body, String script) {
        StringBuilder sb = new StringBuilder(body.length() + 2048);
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,minimum-scale=0.25,maximum-scale=10,user-scalable=yes\">")
                .append("<style>").append(BASE_CSS);
        if (extraCss != null) sb.append(extraCss);
        sb.append("</style></head><body class=\"").append(bodyClass).append("\">")
                .append(body);
        if (script != null) sb.append("<script>").append(script).append("</script>");
        sb.append("</body></html>");
        return sb.toString();
    }

    public static String message(String title, String detail) {
        String body = "<div style=\"padding:48px 24px;text-align:center;font-family:sans-serif\">"
                + "<div style=\"font-size:46px\">&#128196;</div>"
                + "<h3 style=\"margin:12px 0 8px\">" + Util.esc(title) + "</h3>"
                + "<div style=\"color:#555;font-size:14px;line-height:1.5\">" + Util.esc(detail == null ? "" : detail) + "</div></div>";
        return page("msg", "body{background:#fff}", body, null);
    }
}
