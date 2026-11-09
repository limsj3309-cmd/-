package com.limsj.docviewer.core;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Input guard for untrusted XML: rejects DTDs (so entity-expansion "billion laughs" payloads
 * never reach the parser) and caps the number of bytes read (zip bombs). Zero bytes are
 * skipped by the matcher so UTF-16 encoded markup is caught as well.
 */
final class SafeXmlStream extends FilterInputStream {
    static final long MAX_BYTES = 120L * 1024 * 1024;
    private static final byte[][] BANNED = {"<!DOCTYPE".getBytes(), "<!ENTITY".getBytes()};

    private final int[] match = new int[BANNED.length];
    private long total;

    SafeXmlStream(InputStream in) {
        super(in);
    }

    private void check(int b) throws IOException {
        if (b == 0) return;
        for (int i = 0; i < BANNED.length; i++) {
            byte[] pat = BANNED[i];
            if (b == pat[match[i]]) {
                if (++match[i] == pat.length) throw new IOException("XML DTDs are not allowed");
            } else {
                match[i] = b == pat[0] ? 1 : 0;
            }
        }
    }

    private void count(long n) throws IOException {
        total += n;
        if (total > MAX_BYTES) throw new IOException("XML part too large");
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            count(1);
            check(b);
        }
        return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        int n = super.read(buf, off, len);
        if (n > 0) {
            count(n);
            for (int i = off; i < off + n; i++) check(buf[i] & 0xFF);
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        // Never skip unchecked bytes.
        long done = 0;
        byte[] tmp = new byte[4096];
        while (done < n) {
            int r = read(tmp, 0, (int) Math.min(tmp.length, n - done));
            if (r <= 0) break;
            done += r;
        }
        return done;
    }
}
