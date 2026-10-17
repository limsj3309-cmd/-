package com.limsj.docviewer.core;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Read-only OLE2 / Compound File Binary reader (used by HWP, DOC, XLS, PPT). */
public final class Cfb implements Closeable {
    private static final int END = -2;
    public static final long MAX_STREAM = 256L * 1024 * 1024;

    private final RandomAccessFile raf;
    private final int sectorSize, miniSectorSize, miniCutoff;
    private final int[] fat;
    private int[] miniFat = new int[0];
    private byte[] miniStream = new byte[0];
    private final Map<String, Entry> entries = new HashMap<>();
    private final long fileLen;

    private static final class Entry {
        String name;
        int type, left, right, child, start;
        long size;
    }

    public static boolean isCfb(byte[] head) {
        return head.length >= 8 && (head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF && (head[2] & 0xFF) == 0x11
                && (head[3] & 0xFF) == 0xE0 && (head[4] & 0xFF) == 0xA1 && (head[5] & 0xFF) == 0xB1
                && (head[6] & 0xFF) == 0x1A && (head[7] & 0xFF) == 0xE1;
    }

    public Cfb(File f) throws IOException {
        raf = new RandomAccessFile(f, "r");
        try {
            fileLen = raf.length();
            byte[] h = new byte[512];
            raf.readFully(h);
            if (!isCfb(h)) throw new IOException("not a compound file");
            int ss = Util.u16(h, 0x1E);
            if (ss != 9 && ss != 12) throw new IOException("bad sector shift");
            sectorSize = 1 << ss;
            int ms = Util.u16(h, 0x20);
            miniSectorSize = (ms > 0 && ms < 16) ? 1 << ms : 64;
            int numFat = Util.i32(h, 0x2C);
            int firstDir = Util.i32(h, 0x30);
            int cutoff = Util.i32(h, 0x38);
            miniCutoff = cutoff > 0 ? cutoff : 4096;
            int firstMiniFat = Util.i32(h, 0x3C);
            int firstDifat = Util.i32(h, 0x44);
            int numDifat = Util.i32(h, 0x48);

            long maxSectors = fileLen / sectorSize + 1;
            if (numFat < 0 || numFat > maxSectors) throw new IOException("bad FAT count");

            ArrayList<Integer> fatSectors = new ArrayList<>();
            for (int i = 0; i < 109 && fatSectors.size() < numFat; i++) {
                int s = Util.i32(h, 0x4C + i * 4);
                if (s >= 0) fatSectors.add(s);
            }
            int d = firstDifat;
            Set<Integer> seen = new HashSet<>();
            byte[] sec = new byte[sectorSize];
            int per = sectorSize / 4 - 1;
            for (int n = 0; d >= 0 && n < numDifat + 1 && fatSectors.size() < numFat && seen.add(d); n++) {
                readSector(d, sec);
                for (int i = 0; i < per && fatSectors.size() < numFat; i++) {
                    int s = Util.i32(sec, i * 4);
                    if (s >= 0) fatSectors.add(s);
                }
                d = Util.i32(sec, per * 4);
            }
            int perFat = sectorSize / 4;
            fat = new int[fatSectors.size() * perFat];
            for (int i = 0; i < fatSectors.size(); i++) {
                readSector(fatSectors.get(i), sec);
                for (int j = 0; j < perFat; j++) fat[i * perFat + j] = Util.i32(sec, j * 4);
            }

            byte[] dir = readChain(firstDir, -1);
            ArrayList<Entry> list = new ArrayList<>();
            for (int o = 0; o + 128 <= dir.length; o += 128) {
                Entry e = new Entry();
                int nl = Util.u16(dir, o + 64);
                e.name = nl >= 2 ? Util.utf16(dir, o, Math.min(32, nl / 2 - 1)) : "";
                e.type = dir[o + 66] & 0xFF;
                e.left = Util.i32(dir, o + 68);
                e.right = Util.i32(dir, o + 72);
                e.child = Util.i32(dir, o + 76);
                e.start = Util.i32(dir, o + 116);
                e.size = sectorSize == 512 ? Util.u32(dir, o + 120) : Util.i64(dir, o + 120);
                list.add(e);
            }
            if (list.isEmpty()) throw new IOException("empty directory");
            Entry root = list.get(0);
            if (root.type == 5 && root.size > 0) {
                miniStream = readChain(root.start, root.size);
                if (firstMiniFat >= 0) {
                    byte[] mf = readChain(firstMiniFat, -1);
                    miniFat = new int[mf.length / 4];
                    for (int i = 0; i < miniFat.length; i++) miniFat[i] = Util.i32(mf, i * 4);
                }
            }
            walk(list, root.child, "", new HashSet<>());
        } catch (IOException | RuntimeException e) {
            raf.close();
            throw e;
        }
    }

    private void walk(ArrayList<Entry> list, int idx, String prefix, Set<Integer> seen) {
        // iterative over siblings (left/right tree) with recursion into children
        ArrayList<Integer> stack = new ArrayList<>();
        stack.add(idx);
        while (!stack.isEmpty()) {
            int i = stack.remove(stack.size() - 1);
            if (i < 0 || i >= list.size() || !seen.add(i)) continue;
            Entry e = list.get(i);
            stack.add(e.left);
            stack.add(e.right);
            String path = prefix.isEmpty() ? e.name : prefix + "/" + e.name;
            entries.put(path.toLowerCase(Locale.ROOT), e);
            if (e.type == 1 && prefix.split("/").length < 16) walk(list, e.child, path, seen);
        }
    }

    private void readSector(int s, byte[] buf) throws IOException {
        long off = (long) (s + 1) * sectorSize;
        if (s < 0 || off + buf.length > fileLen) {
            // tolerate truncated files: zero fill
            java.util.Arrays.fill(buf, (byte) 0);
            if (s >= 0 && off < fileLen) {
                raf.seek(off);
                raf.read(buf, 0, (int) (fileLen - off));
            }
            return;
        }
        raf.seek(off);
        raf.readFully(buf);
    }

    private byte[] readChain(int start, long size) throws IOException {
        if (size > MAX_STREAM) throw new IOException("stream too large");
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(size > 0 ? (int) size : sectorSize * 4);
        byte[] sec = new byte[sectorSize];
        int s = start;
        int guard = fat.length + 1;
        while (s >= 0 && s < fat.length && guard-- > 0) {
            readSector(s, sec);
            bo.write(sec, 0, sec.length);
            if (size >= 0 && bo.size() >= size) break;
            if (bo.size() > MAX_STREAM) throw new IOException("stream too large");
            s = fat[s];
        }
        byte[] out = bo.toByteArray();
        if (size >= 0 && out.length > size) out = java.util.Arrays.copyOf(out, (int) size);
        return out;
    }

    private byte[] readMini(int start, long size) {
        byte[] out = new byte[(int) size];
        int pos = 0;
        int s = start;
        int guard = miniFat.length + 1;
        while (s >= 0 && pos < size && guard-- > 0) {
            int off = s * miniSectorSize;
            int n = (int) Math.min(miniSectorSize, size - pos);
            if (off + n > miniStream.length) break;
            System.arraycopy(miniStream, off, out, pos, n);
            pos += n;
            s = s < miniFat.length ? miniFat[s] : END;
        }
        return out;
    }

    public boolean has(String path) {
        Entry e = entries.get(path.toLowerCase(Locale.ROOT));
        return e != null && e.type == 2;
    }

    /** Reads a stream by path ("BodyText/Section0"), case-insensitive. Null if missing. */
    public byte[] read(String path) throws IOException {
        Entry e = entries.get(path.toLowerCase(Locale.ROOT));
        if (e == null || e.type != 2) return null;
        if (e.size > MAX_STREAM) throw new IOException("stream too large");
        if (e.size < miniCutoff) return readMini(e.start, e.size);
        return readChain(e.start, e.size);
    }

    public ArrayList<String> list() {
        return new ArrayList<>(entries.keySet());
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }
}
