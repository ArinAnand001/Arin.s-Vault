package com.anand.vault;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Drive REST calls for the app's private, hidden "appDataFolder".
 * Files there count toward the user's own Drive storage but don't show up in Drive or Photos.
 */
final class Drive {
    static final String API = "https://www.googleapis.com/drive/v3/files";
    static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/files";
    static final long CHUNK = 8L * 1024 * 1024; // must be a multiple of 256 KB

    interface SessionStore {
        String get();

        void put(String session);
    }

    interface Cancel {
        boolean cancelled();
    }

    final String token;

    Drive(String token) {
        this.token = token;
    }

    /** Lists every file in the app folder: id, name, size, modifiedTime. */
    List<JSONObject> listAll() throws IOException, JSONException {
        List<JSONObject> out = new ArrayList<>();
        String page = null;
        do {
            String url = API + "?spaces=appDataFolder&pageSize=1000"
                    + "&fields=" + enc("nextPageToken,files(id,name,size,modifiedTime)")
                    + (page == null ? "" : "&pageToken=" + enc(page));
            JSONObject o = new JSONObject(Http.getText(url, token));
            JSONArray files = o.optJSONArray("files");
            if (files != null) for (int i = 0; i < files.length(); i++) out.add(files.getJSONObject(i));
            page = o.optString("nextPageToken", "");
            if (page.isEmpty()) page = null;
        } while (page != null);
        return out;
    }

    String downloadText(String fileId) throws IOException {
        return Http.getText(API + "/" + enc(fileId) + "?alt=media", token);
    }

    void download(String fileId, File dest, long expected, Http.Progress p) throws IOException {
        Http.download(API + "/" + enc(fileId) + "?alt=media", token, dest, expected, p);
    }

    void delete(String fileId) throws IOException {
        HttpURLConnection c = Http.open(API + "/" + enc(fileId), "DELETE", token);
        try {
            int code = c.getResponseCode();
            if (code == 404 || (code >= 200 && code < 300)) return;
            Http.readBody(c);
        } finally {
            c.disconnect();
        }
    }

    /** Creates a small file in one request. Returns its file ID. */
    String createSmall(String name, String mime, byte[] data) throws IOException, JSONException {
        String boundary = "vault" + Long.toHexString(System.nanoTime());
        JSONObject meta = new JSONObject();
        meta.put("name", name);
        meta.put("parents", new JSONArray().put("appDataFolder"));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String head = "--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + meta
                + "\r\n--" + boundary + "\r\nContent-Type: " + mime + "\r\n\r\n";
        body.write(head.getBytes(StandardCharsets.UTF_8));
        body.write(data);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] bytes = body.toByteArray();

        HttpURLConnection c = Http.open(UPLOAD + "?uploadType=multipart&fields=id", "POST", token);
        try {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "multipart/related; boundary=" + boundary);
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(bytes);
            }
            return new JSONObject(Http.readBody(c)).getString("id");
        } finally {
            c.disconnect();
        }
    }

    /**
     * Uploads a (possibly large) file in 8 MB pieces. The upload session is saved, so an
     * interrupted upload continues where it stopped instead of starting over.
     */
    String uploadLarge(File src, String name, String mime, SessionStore store, Http.Progress p, Cancel cancel)
            throws IOException, JSONException {
        long len = src.length();
        String session = store.get();
        long offset = 0;
        if (session != null && !session.isEmpty()) {
            Object st = queryStatus(session, len);
            if (st instanceof String) {
                store.put(null);
                return (String) st;
            }
            offset = (Long) st;
            if (offset < 0) session = null;
        }
        if (session == null || session.isEmpty()) {
            session = startSession(name, mime, len);
            store.put(session);
            offset = 0;
        }
        int retries = 0;
        try (RandomAccessFile raf = new RandomAccessFile(src, "r")) {
            byte[] buf = new byte[256 * 1024];
            while (true) {
                if (cancel != null && cancel.cancelled()) throw new IOException("Backup paused");
                long size = Math.min(CHUNK, len - offset);
                HttpURLConnection c = Http.open(session, "PUT", null);
                c.setInstanceFollowRedirects(false);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(size);
                if (len == 0) c.setRequestProperty("Content-Range", "bytes */0");
                else c.setRequestProperty("Content-Range", "bytes " + offset + "-" + (offset + size - 1) + "/" + len);
                int code;
                try {
                    try (OutputStream out = c.getOutputStream()) {
                        raf.seek(offset);
                        long left = size;
                        while (left > 0) {
                            int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                            if (n < 0) throw new IOException("File changed while uploading");
                            out.write(buf, 0, n);
                            left -= n;
                        }
                    }
                    code = c.getResponseCode();
                } catch (IOException e) {
                    c.disconnect();
                    if (++retries > 4) throw e;
                    sleep(2000L * retries);
                    Object st = queryStatus(session, len);
                    if (st instanceof String) {
                        store.put(null);
                        return (String) st;
                    }
                    offset = (Long) st;
                    if (offset < 0) throw e;
                    continue;
                }
                try {
                    if (code == 308) {
                        offset = nextOffset(c.getHeaderField("Range"));
                        retries = 0;
                        if (p != null) p.bytes(offset, len);
                    } else if (code == 200 || code == 201) {
                        String id = new JSONObject(Http.readBody(c)).getString("id");
                        store.put(null);
                        if (p != null) p.bytes(len, len);
                        return id;
                    } else if (code == 404 || code == 410) {
                        store.put(null);
                        throw new IOException("Upload expired, will start again");
                    } else {
                        Http.readBody(c);
                        throw new Http.HttpError(code, "");
                    }
                } finally {
                    c.disconnect();
                }
            }
        }
    }

    private String startSession(String name, String mime, long len) throws IOException, JSONException {
        JSONObject meta = new JSONObject();
        meta.put("name", name);
        meta.put("parents", new JSONArray().put("appDataFolder"));
        byte[] body = meta.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = Http.open(UPLOAD + "?uploadType=resumable&fields=id", "POST", token);
        try {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            c.setRequestProperty("X-Upload-Content-Type", mime);
            c.setRequestProperty("X-Upload-Content-Length", Long.toString(len));
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            Http.readBody(c);
            String loc = c.getHeaderField("Location");
            if (loc == null || loc.isEmpty()) throw new IOException("Drive didn't start the upload");
            return loc;
        } finally {
            c.disconnect();
        }
    }

    /** Returns the finished file's ID (String), the next byte to send (Long), or -1L if the session is gone. */
    private Object queryStatus(String session, long len) {
        HttpURLConnection c = null;
        try {
            c = Http.open(session, "PUT", null);
            c.setInstanceFollowRedirects(false);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(0);
            c.setRequestProperty("Content-Range", "bytes */" + len);
            c.getOutputStream().close();
            int code = c.getResponseCode();
            if (code == 200 || code == 201) return new JSONObject(Http.readBody(c)).getString("id");
            if (code == 308) return nextOffset(c.getHeaderField("Range"));
            return -1L;
        } catch (Exception e) {
            return -1L;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** "bytes=0-1234" means bytes up to 1234 arrived, so continue at 1235. No header means start at 0. */
    static long nextOffset(String range) {
        if (range == null) return 0;
        int dash = range.lastIndexOf('-');
        if (dash < 0) return 0;
        try {
            return Long.parseLong(range.substring(dash + 1).trim()) + 1;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
