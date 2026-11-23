package com.anand.vault;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Small HTTP helpers built on HttpURLConnection (no extra libraries). */
final class Http {
    static final int CONNECT_TIMEOUT = 20_000;
    static final int READ_TIMEOUT = 60_000;

    interface Progress {
        void bytes(long done, long total);
    }

    static final class HttpError extends IOException {
        final int code;
        final String body;

        HttpError(int code, String body) {
            super("HTTP " + code + (body == null || body.isEmpty() ? "" : ": " + shorten(body)));
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    private Http() {}

    static String shorten(String s) {
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    static HttpURLConnection open(String url, String method, String token) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setRequestMethod(method);
        c.setUseCaches(false);
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }

    /** Reads the response body; throws HttpError for codes outside 200-299. */
    static String readBody(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = in == null ? "" : readAll(in);
        if (code < 200 || code > 299) throw new HttpError(code, body);
        return body;
    }

    static String readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static String getText(String url) throws IOException {
        return getText(url, null);
    }

    static String getText(String url, String token) throws IOException {
        HttpURLConnection c = open(url, "GET", token);
        try {
            return readBody(c);
        } finally {
            c.disconnect();
        }
    }

    /** Downloads to dest via a .part file, so a broken download never looks complete. */
    static void download(String url, String token, File dest, long expected, Progress p) throws IOException {
        HttpURLConnection c = open(url, "GET", token);
        File part = new File(dest.getPath() + ".part");
        try {
            int code = c.getResponseCode();
            if (code < 200 || code > 299) {
                InputStream err = c.getErrorStream();
                throw new HttpError(code, err == null ? "" : readAll(err));
            }
            long total = c.getContentLength();
            if (total <= 0) total = expected;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                copy(in, out, total, p);
            }
            if (dest.exists() && !dest.delete()) throw new IOException("Couldn't replace " + dest.getName());
            if (!part.renameTo(dest)) throw new IOException("Couldn't save " + dest.getName());
        } finally {
            c.disconnect();
            if (part.exists()) part.delete();
        }
    }

    static void copy(InputStream in, OutputStream out, long total, Progress p) throws IOException {
        byte[] buf = new byte[256 * 1024];
        long done = 0;
        long last = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            done += n;
            long now = System.currentTimeMillis();
            if (p != null && now - last > 300) {
                last = now;
                p.bytes(done, total);
            }
        }
        if (p != null) p.bytes(done, total);
    }
}
