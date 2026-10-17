package com.limsj.docviewer.core;

import java.io.Closeable;

/** Result of a conversion: an HTML page plus a resolver for the resources it references under "res/". */
public final class Doc implements Closeable {
    public interface Res {
        /** Returns resource bytes for a path referenced as "res/&lt;path&gt;", or null. */
        byte[] get(String path) throws Exception;
    }

    public final String html;
    public final Res res;
    private final Closeable closer;

    public Doc(String html, Res res, Closeable closer) {
        this.html = html;
        this.res = res;
        this.closer = closer;
    }

    @Override
    public void close() {
        if (closer != null) {
            try {
                closer.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** User-facing error that should be shown as-is. */
    public static final class UserError extends Exception {
        public UserError(String msg) {
            super(msg);
        }
    }
}
