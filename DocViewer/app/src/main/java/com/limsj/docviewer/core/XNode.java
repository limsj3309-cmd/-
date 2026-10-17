package com.limsj.docviewer.core;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal, namespace-agnostic XML tree. Element and attribute names are stored
 * as local names (prefix stripped) so "w:p", "a:p" and "hp:p" all become "p".
 * mc:AlternateContent is resolved while parsing (first Choice wins), which
 * avoids rendering the same content twice.
 */
public final class XNode {
    public static final String TEXT = "#t";
    private static final ArrayList<XNode> NONE = new ArrayList<>(0);

    public final String name;
    private String[] attrs; // triples: local name, namespace, value
    private ArrayList<XNode> kids;
    public String text;
    public XNode parent;

    private XNode(String name) {
        this.name = name;
    }

    public static XmlPullParser newParser(InputStream in) throws Exception {
        XmlPullParserFactory f = XmlPullParserFactory.newInstance();
        f.setNamespaceAware(true);
        XmlPullParser p = f.newPullParser();
        p.setInput(in, null);
        return p;
    }

    public static XNode parse(InputStream in) throws Exception {
        XmlPullParser p = newParser(in);
        XNode root = new XNode("#root");
        XNode cur = root;
        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                XNode n = new XNode(p.getName());
                int ac = p.getAttributeCount();
                if (ac > 0) {
                    n.attrs = new String[ac * 3];
                    for (int i = 0; i < ac; i++) {
                        n.attrs[i * 3] = p.getAttributeName(i);
                        n.attrs[i * 3 + 1] = p.getAttributeNamespace(i);
                        n.attrs[i * 3 + 2] = p.getAttributeValue(i);
                    }
                }
                cur.add(n);
                cur = n;
            } else if (ev == XmlPullParser.END_TAG) {
                XNode done = cur;
                cur = cur.parent != null ? cur.parent : root;
                if ("AlternateContent".equals(done.name)) resolveAlternate(cur, done);
            } else if (ev == XmlPullParser.TEXT) {
                String t = p.getText();
                if (t != null && !t.isEmpty() && (isTextHolder(cur.name) || !t.trim().isEmpty())) {
                    XNode tn = new XNode(TEXT);
                    tn.text = t;
                    cur.add(tn);
                }
            }
            ev = p.next();
        }
        return root;
    }

    private static boolean isTextHolder(String n) {
        return "t".equals(n) || "instrText".equals(n);
    }

    private static void resolveAlternate(XNode parent, XNode alt) {
        XNode pick = alt.child("Choice");
        if (pick == null) pick = alt.child("Fallback");
        int idx = parent.kids.indexOf(alt);
        parent.kids.remove(idx);
        if (pick != null && pick.kids != null) {
            for (XNode k : pick.kids) {
                k.parent = parent;
                parent.kids.add(idx++, k);
            }
        }
    }

    private void add(XNode n) {
        if (kids == null) kids = new ArrayList<>(4);
        n.parent = this;
        kids.add(n);
    }

    public List<XNode> kids() {
        return kids == null ? NONE : kids;
    }

    public String attr(String n) {
        if (attrs == null) return null;
        for (int i = 0; i < attrs.length; i += 3) if (attrs[i].equals(n)) return attrs[i + 2];
        return null;
    }

    /** Relationship-namespace attribute (r:id, r:embed, ...), falling back to any attribute with that name. */
    public String rel(String n) {
        if (attrs == null) return null;
        for (int i = 0; i < attrs.length; i += 3) {
            String ns = attrs[i + 1];
            if (attrs[i].equals(n) && ns != null && ns.contains("relationships")) return attrs[i + 2];
        }
        return attr(n);
    }

    public String attr(String n, String def) {
        String v = attr(n);
        return v == null ? def : v;
    }

    public int intAttr(String n, int def) {
        return Util.parseInt(attr(n), def);
    }

    public long longAttr(String n, long def) {
        String v = attr(n);
        if (v == null) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    public XNode child(String n) {
        if (kids == null) return null;
        for (XNode k : kids) if (k.name.equals(n)) return k;
        return null;
    }

    public List<XNode> children(String n) {
        ArrayList<XNode> out = new ArrayList<>();
        if (kids != null) for (XNode k : kids) if (k.name.equals(n)) out.add(k);
        return out;
    }

    /** Path of direct children, e.g. path("rPr", "sz"). */
    public XNode path(String... names) {
        XNode c = this;
        for (String n : names) {
            if (c == null) return null;
            c = c.child(n);
        }
        return c;
    }

    /** First descendant (depth first) with the given name. */
    public XNode find(String n) {
        if (kids == null) return null;
        for (XNode k : kids) {
            if (k.name.equals(n)) return k;
            XNode r = k.find(n);
            if (r != null) return r;
        }
        return null;
    }

    public List<XNode> findAll(String n) {
        ArrayList<XNode> out = new ArrayList<>();
        findAll(n, out);
        return out;
    }

    private void findAll(String n, List<XNode> out) {
        if (kids == null) return;
        for (XNode k : kids) {
            if (k.name.equals(n)) out.add(k);
            else k.findAll(n, out);
        }
    }

    public String allText() {
        StringBuilder sb = new StringBuilder();
        allText(sb);
        return sb.toString();
    }

    private void allText(StringBuilder sb) {
        if (text != null) sb.append(text);
        if (kids != null) for (XNode k : kids) k.allText(sb);
    }

    public boolean isText() {
        return TEXT.equals(name);
    }
}
