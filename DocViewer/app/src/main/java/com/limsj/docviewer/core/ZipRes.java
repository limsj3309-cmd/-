package com.limsj.docviewer.core;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Zip package access shared by DOCX/XLSX/PPTX/HWPX. */
public final class ZipRes implements Closeable, Doc.Res {
    public static final long MAX_PART = 200L * 1024 * 1024;
    public final ZipFile zip;
    private final Map<String, String> lower = new HashMap<>();

    public ZipRes(File f) throws IOException {
        zip = new ZipFile(f);
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            String n = en.nextElement().getName();
            lower.put(n.toLowerCase(Locale.ROOT), n);
        }
    }

    private ZipEntry entry(String path) {
        if (path == null) return null;
        if (path.startsWith("/")) path = path.substring(1);
        ZipEntry e = zip.getEntry(path);
        if (e == null) {
            String real = lower.get(path.toLowerCase(Locale.ROOT));
            if (real != null) e = zip.getEntry(real);
        }
        return e;
    }

    public boolean has(String path) {
        return entry(path) != null;
    }

    public InputStream open(String path) throws IOException {
        ZipEntry e = entry(path);
        return e == null ? null : zip.getInputStream(e);
    }

    public XNode xml(String path) throws Exception {
        InputStream in = open(path);
        if (in == null) return null;
        try {
            return XNode.parse(in);
        } finally {
            in.close();
        }
    }

    public byte[] bytes(String path) throws IOException {
        InputStream in = open(path);
        if (in == null) return null;
        try {
            return Util.readAll(in, MAX_PART);
        } finally {
            in.close();
        }
    }

    public Iterable<String> names() {
        return lower.values();
    }

    /** Relationship map (Id -> resolved part path) for a part, e.g. "word/document.xml". */
    public Map<String, String> rels(String part) {
        Map<String, String> m = new HashMap<>();
        String dir = Util.dirOf(part);
        String name = part.substring(part.lastIndexOf('/') + 1);
        String relPath = (dir.isEmpty() ? "" : dir + "/") + "_rels/" + name + ".rels";
        try {
            XNode r = xml(relPath);
            if (r == null) return m;
            for (XNode rel : r.findAll("Relationship")) {
                if ("External".equalsIgnoreCase(rel.attr("TargetMode"))) {
                    m.put(rel.attr("Id"), "ext:" + rel.attr("Target"));
                } else {
                    m.put(rel.attr("Id"), Util.resolve(dir, rel.attr("Target")));
                }
            }
        } catch (Exception ignored) {
        }
        return m;
    }

    /** Finds the first rel target with a type ending in the given suffix. */
    public String relByType(String part, String typeSuffix) {
        String dir = Util.dirOf(part);
        String name = part.substring(part.lastIndexOf('/') + 1);
        String relPath = (dir.isEmpty() ? "" : dir + "/") + "_rels/" + name + ".rels";
        try {
            XNode r = xml(relPath);
            if (r == null) return null;
            for (XNode rel : r.findAll("Relationship")) {
                String t = rel.attr("Type", "");
                if (t.endsWith(typeSuffix)) return Util.resolve(dir, rel.attr("Target"));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    public byte[] get(String path) throws Exception {
        return bytes(path);
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}
