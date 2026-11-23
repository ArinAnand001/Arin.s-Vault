package com.anand.vault;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.StatFs;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * The vault on this phone: the list of photos/videos and albums (library.json),
 * the files themselves, the PIN and the settings.
 *
 * All files live in the app's private storage, which Android deletes when the app is
 * uninstalled. Google accounts get their copy back from Drive (see Sync).
 */
final class Vault {
    static final long DAY = 24L * 60 * 60 * 1000;
    static final long BIN_DAYS = 30;

    private static Vault instance;

    static synchronized Vault get(Context c) {
        if (instance == null) instance = new Vault(c.getApplicationContext());
        return instance;
    }

    final Context ctx;
    final File root;
    final File mediaDir;
    final File thumbDir;
    final File viewDir;
    final SharedPreferences prefs;
    private final File libFile;
    private final SecureRandom random = new SecureRandom();
    private JSONObject lib;
    private volatile long changes;

    private Vault(Context c) {
        ctx = c;
        root = new File(c.getFilesDir(), "vault");
        mediaDir = new File(root, "media");
        thumbDir = new File(root, "thumbs");
        viewDir = new File(root, "view");
        makeDirs();
        libFile = new File(root, "library.json");
        prefs = c.getSharedPreferences("vault", Context.MODE_PRIVATE);
        lib = load();
    }

    private void makeDirs() {
        mediaDir.mkdirs();
        thumbDir.mkdirs();
        viewDir.mkdirs();
    }

    // ---------------------------------------------------------------- library file

    private JSONObject load() {
        JSONObject o = null;
        try {
            if (libFile.exists()) o = new JSONObject(readText(libFile));
        } catch (Exception ignored) {
        }
        if (o == null) o = new JSONObject();
        normalize(o);
        return o;
    }

    static void normalize(JSONObject o) {
        try {
            if (o.optJSONArray("items") == null) o.put("items", new JSONArray());
            if (o.optJSONArray("albums") == null) o.put("albums", new JSONArray());
            if (o.optJSONObject("gone") == null) o.put("gone", new JSONObject());
            if (o.optJSONObject("goneAlbums") == null) o.put("goneAlbums", new JSONObject());
        } catch (JSONException ignored) {
        }
    }

    synchronized void save() {
        changes++;
        try {
            makeDirs();
            File tmp = new File(root, "library.json.tmp");
            writeText(tmp, lib.toString());
            if (!tmp.renameTo(libFile)) {
                writeText(libFile, lib.toString());
                tmp.delete();
            }
        } catch (IOException ignored) {
        }
    }

    long changeCount() {
        return changes;
    }

    static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return Http.readAll(in);
        }
    }

    static void writeText(File f, String s) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    // ---------------------------------------------------------------- settings & account

    String mode() {
        return prefs.getString("mode", "");
    }

    boolean isGoogle() {
        return "google".equals(mode());
    }

    String email() {
        return prefs.getString("email", "");
    }

    boolean secure() {
        return prefs.getBoolean("secure", true);
    }

    boolean wifiOnly() {
        return prefs.getBoolean("wifiOnly", false);
    }

    long lockDelayMs() {
        return prefs.getInt("lockDelay", 0) * 1000L;
    }

    void setSetting(String key, String value) {
        SharedPreferences.Editor e = prefs.edit();
        switch (key) {
            case "secure":
            case "wifiOnly":
            case "notifAsked":
                e.putBoolean(key, "true".equals(value));
                break;
            case "lockDelay":
                int v = 0;
                try {
                    v = Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                }
                e.putInt(key, Math.max(0, Math.min(v, 3600)));
                break;
            default:
                return;
        }
        e.apply();
    }

    void setGuest() {
        prefs.edit().putString("mode", "guest").remove("email").remove("name").remove("photo").apply();
    }

    void setGoogle(String email, String name, String photo) {
        prefs.edit().putString("mode", "google").putString("email", email)
                .putString("name", name == null ? "" : name).putString("photo", photo == null ? "" : photo)
                .remove("manifestId").remove("manifestHash").apply();
    }

    JSONObject state() {
        JSONObject s = Events.obj(
                "mode", mode(),
                "email", email(),
                "name", prefs.getString("name", ""),
                "photo", prefs.getString("photo", ""),
                "pinSet", pinSet(),
                "lockDelay", prefs.getInt("lockDelay", 0),
                "secure", secure(),
                "wifiOnly", wifiOnly(),
                "notifAsked", prefs.getBoolean("notifAsked", false),
                "sdk", Build.VERSION.SDK_INT,
                "appName", BuildConfig.APP_NAME,
                "versionName", BuildConfig.VERSION_NAME,
                "versionCode", BuildConfig.VERSION_CODE,
                "updatesOn", !BuildConfig.REPO.isEmpty());
        return s;
    }

    /** Deletes every photo, video, album and setting on this phone. */
    synchronized void wipe() {
        deleteTree(root);
        makeDirs();
        lib = new JSONObject();
        normalize(lib);
        save();
        prefs.edit().clear().apply();
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    // ---------------------------------------------------------------- PIN

    boolean pinSet() {
        return !prefs.getString("pinHash", "").isEmpty();
    }

    synchronized void setPin(String pin) {
        if (pin == null || !pin.matches("\\d{4}")) throw new IllegalArgumentException("PIN must be 4 digits");
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        Base64.Encoder b64 = Base64.getEncoder();
        prefs.edit().putString("pinSalt", b64.encodeToString(salt))
                .putString("pinHash", b64.encodeToString(hashPin(pin, salt)))
                .putInt("pinFails", 0).putLong("pinUntil", 0).apply();
    }

    synchronized JSONObject checkPin(String pin) {
        long now = System.currentTimeMillis();
        long until = prefs.getLong("pinUntil", 0);
        if (now < until) return Events.obj("ok", false, "wait", (until - now + 999) / 1000);
        boolean ok = false;
        try {
            byte[] salt = Base64.getDecoder().decode(prefs.getString("pinSalt", ""));
            byte[] want = Base64.getDecoder().decode(prefs.getString("pinHash", ""));
            ok = pin != null && pin.matches("\\d{4}") && MessageDigest.isEqual(want, hashPin(pin, salt));
        } catch (Exception ignored) {
        }
        if (ok) {
            prefs.edit().putInt("pinFails", 0).putLong("pinUntil", 0).apply();
            return Events.obj("ok", true);
        }
        int fails = prefs.getInt("pinFails", 0) + 1;
        long wait = 0;
        if (fails >= 5) wait = 30_000L << Math.min(fails - 5, 5); // 30 s, 1 min, 2 min ... up to 16 min
        prefs.edit().putInt("pinFails", fails).putLong("pinUntil", wait > 0 ? now + wait : 0).apply();
        return Events.obj("ok", false, "wait", wait / 1000, "left", Math.max(0, 5 - fails));
    }

    static byte[] hashPin(String pin, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, 20_000, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- files

    File original(JSONObject it) {
        return new File(mediaDir, it.optString("id") + "." + it.optString("ext", "bin"));
    }

    File thumb(String id) {
        return new File(thumbDir, id + ".jpg");
    }

    File view(String id) {
        return new File(viewDir, id + ".jpg");
    }

    private void deleteLocalFiles(JSONObject it) {
        String id = it.optString("id");
        original(it).delete();
        thumb(id).delete();
        view(id).delete();
    }

    String newId() {
        byte[] b = new byte[4];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder(Long.toString(System.currentTimeMillis(), 36)).append('-');
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }

    // ---------------------------------------------------------------- import

    JSONObject importUri(Uri uri, String albumId, Http.Progress p) throws IOException, JSONException {
        ContentResolver cr = ctx.getContentResolver();
        String mime = cr.getType(uri);
        String name = null;
        long size = -1;
        try (Cursor cur = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int ni = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ni >= 0 && !cur.isNull(ni)) name = cur.getString(ni);
                int si = cur.getColumnIndex(OpenableColumns.SIZE);
                if (si >= 0 && !cur.isNull(si)) size = cur.getLong(si);
            }
        } catch (Exception ignored) {
        }
        String ext = extension(name, mime);
        if (mime == null || mime.isEmpty() || "application/octet-stream".equals(mime)) {
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (m != null) mime = m;
        }
        if (mime == null) mime = "application/octet-stream";
        boolean video = mime.startsWith("video/");
        if (!video && !mime.startsWith("image/")) throw new IOException("Only photos and videos can be added");
        if (size > 0 && size + 50L * 1024 * 1024 > freeBytes()) throw new IOException("Not enough space on this phone");

        makeDirs();
        String id = newId();
        File out = new File(mediaDir, id + "." + ext);
        File part = new File(mediaDir, id + ".part");
        try (InputStream in = cr.openInputStream(uri); OutputStream os = new FileOutputStream(part)) {
            if (in == null) throw new IOException("Couldn't open the file");
            Http.copy(in, os, size, p);
        } catch (IOException e) {
            part.delete();
            throw e;
        }
        if (!part.renameTo(out)) {
            part.delete();
            throw new IOException("Couldn't save the file");
        }

        long now = System.currentTimeMillis();
        JSONObject it = new JSONObject();
        it.put("id", id);
        it.put("type", video ? "video" : "photo");
        it.put("mime", mime);
        it.put("name", name == null || name.isEmpty() ? id + "." + ext : name);
        it.put("size", out.length());
        it.put("ext", ext);
        it.put("added", now);
        it.put("updated", now);
        JSONArray albums = new JSONArray();
        if (albumId != null && !albumId.isEmpty() && album(albumId) != null) albums.put(albumId);
        it.put("albums", albums);
        try {
            makeDerived(it, out);
        } catch (Throwable ignored) {
            // A missing thumbnail is not worth failing the import for.
        }
        synchronized (this) {
            lib.getJSONArray("items").put(it);
            save();
        }
        return it;
    }

    static String extension(String name, String mime) {
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String e = name.substring(dot + 1).toLowerCase(Locale.US);
                if (e.matches("[a-z0-9]{1,5}")) return e;
            }
        }
        if (mime != null) {
            String e = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (e != null && !e.isEmpty()) return e;
            if (mime.startsWith("video/")) return "mp4";
            if (mime.startsWith("image/")) return "jpg";
        }
        return "bin";
    }

    long freeBytes() {
        try {
            return new StatFs(ctx.getFilesDir().getAbsolutePath()).getAvailableBytes();
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    /** Fills in size/duration/date and writes the thumbnail (and a viewable copy for HEIC etc.). */
    void makeDerived(JSONObject it, File f) throws JSONException, IOException {
        String id = it.getString("id");
        makeDirs();
        if ("video".equals(it.optString("type"))) {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(f.getAbsolutePath());
                long dur = parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                int w = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                int h = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                int rot = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
                if (rot == 90 || rot == 270) {
                    int t = w;
                    w = h;
                    h = t;
                }
                it.put("dur", dur);
                it.put("w", w);
                it.put("h", h);
                long taken = parseVideoDate(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE));
                if (taken > 0) it.put("taken", taken);
                if (!thumb(id).exists()) {
                    Bitmap b = null;
                    long at = Math.min(1_000_000L, dur * 500L); // 1 s in, or halfway for very short clips
                    if (Build.VERSION.SDK_INT >= 27) {
                        b = r.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640);
                    }
                    if (b == null) b = r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (b == null) b = r.getFrameAtTime();
                    if (b != null) {
                        Bitmap s = scale(b, 400, 900, 0);
                        writeJpeg(s, thumb(id), 82);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                try {
                    r.release();
                } catch (Exception ignored) {
                }
            }
            return;
        }

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int w = o.outWidth;
        int h = o.outHeight;
        int rotation = 0;
        try {
            ExifInterface e = new ExifInterface(f.getAbsolutePath());
            int or = e.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (or == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
            else if (or == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
            else if (or == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
            long taken = parseExifDate(e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
            if (taken > 0) it.put("taken", taken);
        } catch (Exception ignored) {
        }
        if (rotation == 90 || rotation == 270) {
            int t = w;
            w = h;
            h = t;
        }
        if (w > 0 && h > 0) {
            it.put("w", w);
            it.put("h", h);
        }
        if (!thumb(id).exists()) {
            Bitmap t = decode(f, 400, 900, rotation);
            if (t != null) writeJpeg(t, thumb(id), 82);
        }
        String mime = it.optString("mime");
        boolean webSafe = mime.equals("image/jpeg") || mime.equals("image/png") || mime.equals("image/gif")
                || mime.equals("image/webp") || mime.equals("image/bmp");
        if ((!webSafe || Math.max(w, h) > 4096) && !view(id).exists()) {
            Bitmap v = decode(f, 0, 2560, rotation);
            if (v != null) writeJpeg(v, view(id), 90);
        }
    }

    /** Decodes a photo scaled so its short side is ~minSide (if > 0) and its long side at most maxSide. */
    static Bitmap decode(File f, int minSide, int maxSide, int rotation) {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), b);
        int w = b.outWidth;
        int h = b.outHeight;
        if (w <= 0 || h <= 0) return null;
        float scale = scaleFor(w, h, minSide, maxSide);
        int sample = 1;
        while (w / (sample * 2) >= w * scale && h / (sample * 2) >= h * scale) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bm;
        try {
            bm = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (OutOfMemoryError e) {
            return null;
        }
        if (bm == null) return null;
        return scale(bm, minSide, maxSide, rotation);
    }

    static float scaleFor(int w, int h, int minSide, int maxSide) {
        float s = 1f;
        if (minSide > 0) s = Math.min(s, minSide / (float) Math.min(w, h));
        if (maxSide > 0) s = Math.min(s, maxSide / (float) Math.max(w, h));
        return s;
    }

    static Bitmap scale(Bitmap bm, int minSide, int maxSide, int rotation) {
        int w = bm.getWidth();
        int h = bm.getHeight();
        float s = scaleFor(w, h, minSide, maxSide);
        if (s >= 0.999f && rotation == 0) return bm;
        Matrix m = new Matrix();
        m.postScale(s, s);
        if (rotation != 0) m.postRotate(rotation);
        Bitmap out = Bitmap.createBitmap(bm, 0, 0, w, h, m, true);
        if (out != bm) bm.recycle();
        return out;
    }

    static void writeJpeg(Bitmap b, File f, int quality) throws IOException {
        File part = new File(f.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(part)) {
            b.compress(Bitmap.CompressFormat.JPEG, quality, out);
        } finally {
            b.recycle();
        }
        if (!part.renameTo(f)) part.delete();
    }

    static long parseLong(String s) {
        if (s == null) return 0;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static long parseExifDate(String s) {
        if (s == null || s.length() < 19 || s.startsWith("0000")) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
            return f.parse(s.substring(0, 19)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    static long parseVideoDate(String s) {
        if (s == null || s.length() < 15 || s.startsWith("1904") || s.startsWith("1970")) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(s.substring(0, 15)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- items & albums

    synchronized JSONObject item(String id) {
        JSONArray items = lib.optJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null && id.equals(it.optString("id"))) return it;
        }
        return null;
    }

    synchronized JSONObject itemCopy(String id) {
        JSONObject it = item(id);
        try {
            return it == null ? null : new JSONObject(it.toString());
        } catch (JSONException e) {
            return null;
        }
    }

    synchronized JSONObject album(String id) {
        JSONArray a = lib.optJSONArray("albums");
        for (int i = 0; i < a.length(); i++) {
            JSONObject al = a.optJSONObject(i);
            if (al != null && id.equals(al.optString("id"))) return al;
        }
        return null;
    }

    synchronized JSONObject createAlbum(String name) throws JSONException {
        long now = System.currentTimeMillis();
        JSONObject al = new JSONObject();
        al.put("id", "a" + newId());
        al.put("name", cleanName(name));
        al.put("created", now);
        al.put("updated", now);
        lib.getJSONArray("albums").put(al);
        save();
        return al;
    }

    static String cleanName(String n) {
        String s = n == null ? "" : n.trim();
        if (s.isEmpty()) s = "Album";
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    synchronized void renameAlbum(String id, String name) throws JSONException {
        JSONObject al = album(id);
        if (al == null) return;
        al.put("name", cleanName(name));
        al.put("updated", System.currentTimeMillis());
        save();
    }

    /** Deletes the album only; its photos and videos stay in the vault. */
    synchronized void deleteAlbum(String id) throws JSONException {
        long now = System.currentTimeMillis();
        JSONArray albums = lib.getJSONArray("albums");
        JSONArray keep = new JSONArray();
        for (int i = 0; i < albums.length(); i++) {
            JSONObject al = albums.getJSONObject(i);
            if (!id.equals(al.optString("id"))) keep.put(al);
        }
        lib.put("albums", keep);
        lib.getJSONObject("goneAlbums").put(id, now);
        JSONArray items = lib.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            if (removeFrom(it.optJSONArray("albums"), id, it)) it.put("updated", now);
        }
        save();
    }

    private static boolean removeFrom(JSONArray arr, String value, JSONObject owner) throws JSONException {
        if (arr == null) return false;
        JSONArray keep = new JSONArray();
        boolean found = false;
        for (int i = 0; i < arr.length(); i++) {
            String v = arr.optString(i);
            if (value.equals(v)) found = true;
            else keep.put(v);
        }
        if (found) owner.put("albums", keep);
        return found;
    }

    synchronized void addToAlbum(String albumId, Collection<String> ids) throws JSONException {
        if (album(albumId) == null) return;
        long now = System.currentTimeMillis();
        for (String id : ids) {
            JSONObject it = item(id);
            if (it == null) continue;
            JSONArray a = it.optJSONArray("albums");
            if (a == null) {
                a = new JSONArray();
                it.put("albums", a);
            }
            boolean has = false;
            for (int i = 0; i < a.length(); i++) if (albumId.equals(a.optString(i))) has = true;
            if (!has) {
                a.put(albumId);
                it.put("updated", now);
            }
        }
        save();
    }

    synchronized void removeFromAlbum(String albumId, Collection<String> ids) throws JSONException {
        long now = System.currentTimeMillis();
        for (String id : ids) {
            JSONObject it = item(id);
            if (it != null && removeFrom(it.optJSONArray("albums"), albumId, it)) it.put("updated", now);
        }
        save();
    }

    synchronized void trash(Collection<String> ids, boolean toBin) throws JSONException {
        long now = System.currentTimeMillis();
        for (String id : ids) {
            JSONObject it = item(id);
            if (it == null) continue;
            if (toBin) it.put("trashed", now);
            else it.remove("trashed");
            it.put("updated", now);
        }
        save();
    }

    synchronized void deleteForever(Collection<String> ids) throws JSONException {
        Set<String> set = new HashSet<>(ids);
        long now = System.currentTimeMillis();
        JSONArray items = lib.getJSONArray("items");
        JSONArray keep = new JSONArray();
        JSONObject gone = lib.getJSONObject("gone");
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            String id = it.optString("id");
            if (set.contains(id)) {
                gone.put(id, now);
                deleteLocalFiles(it);
            } else {
                keep.put(it);
            }
        }
        lib.put("items", keep);
        save();
    }

    synchronized void emptyBin() throws JSONException {
        List<String> ids = new ArrayList<>();
        JSONArray items = lib.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            if (it.optLong("trashed", 0) > 0) ids.add(it.optString("id"));
        }
        if (!ids.isEmpty()) deleteForever(ids);
    }

    /** Items in the bin for more than 30 days are deleted for good. */
    synchronized void purgeOldBin() throws JSONException {
        long cutoff = System.currentTimeMillis() - BIN_DAYS * DAY;
        List<String> ids = new ArrayList<>();
        JSONArray items = lib.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            long t = it.optLong("trashed", 0);
            if (t > 0 && t < cutoff) ids.add(it.optString("id"));
        }
        if (!ids.isEmpty()) deleteForever(ids);
    }

    // ---------------------------------------------------------------- for the screen

    synchronized JSONObject forJs() throws JSONException {
        JSONArray items = lib.getJSONArray("items");
        JSONArray outItems = new JSONArray();
        String[] keys = {"id", "type", "mime", "name", "size", "w", "h", "dur", "added", "taken", "trashed", "albums"};
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            String id = it.optString("id");
            JSONObject j = new JSONObject();
            for (String k : keys) if (it.has(k)) j.put(k, it.get(k));
            j.put("backedUp", !it.optString("driveId", "").isEmpty());
            File o = original(it);
            j.put("local", o.exists());
            File t = thumb(id);
            if (t.exists()) j.put("thumb", fileUrl(t));
            File v = view(id);
            if (v.exists()) j.put("src", fileUrl(v));
            else if (o.exists()) j.put("src", fileUrl(o));
            outItems.put(j);
        }
        JSONObject out = new JSONObject();
        out.put("items", outItems);
        out.put("albums", new JSONArray(lib.getJSONArray("albums").toString()));
        return out;
    }

    static String fileUrl(File f) {
        return "file://" + f.getAbsolutePath();
    }

    synchronized JSONObject storage() {
        long used = treeSize(root);
        int count = lib.optJSONArray("items").length();
        return Events.obj("used", used, "free", freeBytes(), "count", count);
    }

    static long treeSize(File f) {
        if (f.isFile()) return f.length();
        long n = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) n += treeSize(k);
        return n;
    }

    // ---------------------------------------------------------------- backup support (used by Sync)

    synchronized int[] backupCounts() {
        JSONArray items = lib.optJSONArray("items");
        int total = items.length();
        int done = 0;
        for (int i = 0; i < total; i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null && !it.optString("driveId", "").isEmpty()) done++;
        }
        return new int[]{total, done};
    }

    synchronized List<JSONObject> itemsCopy() {
        List<JSONObject> out = new ArrayList<>();
        JSONArray items = lib.optJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            try {
                out.add(new JSONObject(items.getJSONObject(i).toString()));
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    synchronized boolean isGone(String id) {
        return lib.optJSONObject("gone").has(id);
    }

    synchronized void setDriveIds(String id, String driveId, String thumbId) throws JSONException {
        JSONObject it = item(id);
        if (it == null) return;
        if (driveId != null && !driveId.isEmpty()) it.put("driveId", driveId);
        if (thumbId != null && !thumbId.isEmpty()) it.put("thumbId", thumbId);
        save();
    }

    /** The backup's table of contents: everything except the files themselves. */
    synchronized String manifestJson() throws JSONException {
        JSONObject m = new JSONObject(lib.toString());
        m.put("app", "vault");
        m.put("v", 1);
        return m.toString();
    }

    /** Merges a backup's table of contents into this phone's library. */
    synchronized boolean mergeRemote(JSONObject remote) throws JSONException {
        String before = lib.toString();
        JSONObject merged = merge(lib, remote);
        JSONObject gone = merged.getJSONObject("gone");
        JSONArray items = lib.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            if (gone.has(it.optString("id"))) deleteLocalFiles(it);
        }
        lib = merged;
        boolean changed = !before.equals(lib.toString());
        if (changed) save();
        return changed;
    }

    /**
     * Combines two copies of a library. Deleted-for-good items (and albums) stay deleted;
     * for anything else the most recently changed copy wins, and Drive file IDs are kept
     * from whichever copy has them.
     */
    static JSONObject merge(JSONObject local, JSONObject remote) throws JSONException {
        normalize(local);
        normalize(remote);
        JSONObject gone = mergeStamps(local.getJSONObject("gone"), remote.getJSONObject("gone"));
        JSONObject goneAlbums = mergeStamps(local.getJSONObject("goneAlbums"), remote.getJSONObject("goneAlbums"));

        Map<String, JSONObject> items = new LinkedHashMap<>();
        collect(items, local.getJSONArray("items"));
        JSONArray ri = remote.getJSONArray("items");
        for (int i = 0; i < ri.length(); i++) {
            JSONObject r = ri.optJSONObject(i);
            if (r == null || r.optString("id").isEmpty()) continue;
            String id = r.optString("id");
            JSONObject l = items.get(id);
            if (l == null) {
                items.put(id, new JSONObject(r.toString()));
                continue;
            }
            JSONObject win = r.optLong("updated", 0) > l.optLong("updated", 0) ? new JSONObject(r.toString()) : l;
            JSONObject other = win == l ? r : l;
            for (String k : new String[]{"driveId", "thumbId"}) {
                if (win.optString(k, "").isEmpty() && !other.optString(k, "").isEmpty()) win.put(k, other.getString(k));
            }
            items.put(id, win);
        }

        Map<String, JSONObject> albums = new LinkedHashMap<>();
        collect(albums, local.getJSONArray("albums"));
        JSONArray ra = remote.getJSONArray("albums");
        for (int i = 0; i < ra.length(); i++) {
            JSONObject r = ra.optJSONObject(i);
            if (r == null || r.optString("id").isEmpty()) continue;
            JSONObject l = albums.get(r.optString("id"));
            if (l == null || r.optLong("updated", 0) > l.optLong("updated", 0)) albums.put(r.optString("id"), new JSONObject(r.toString()));
        }
        for (Iterator<String> it = goneAlbums.keys(); it.hasNext(); ) albums.remove(it.next());

        JSONArray outItems = new JSONArray();
        for (JSONObject it : items.values()) {
            if (gone.has(it.optString("id"))) continue;
            JSONArray a = it.optJSONArray("albums");
            if (a != null) {
                JSONArray keep = new JSONArray();
                for (int i = 0; i < a.length(); i++) if (albums.containsKey(a.optString(i))) keep.put(a.optString(i));
                it.put("albums", keep);
            }
            outItems.put(it);
        }
        JSONArray outAlbums = new JSONArray();
        for (JSONObject al : albums.values()) outAlbums.put(al);

        JSONObject out = new JSONObject();
        out.put("items", outItems);
        out.put("albums", outAlbums);
        out.put("gone", gone);
        out.put("goneAlbums", goneAlbums);
        return out;
    }

    private static void collect(Map<String, JSONObject> into, JSONArray arr) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && !o.optString("id").isEmpty()) into.put(o.optString("id"), o);
        }
    }

    private static JSONObject mergeStamps(JSONObject a, JSONObject b) throws JSONException {
        JSONObject out = new JSONObject(a.toString());
        for (Iterator<String> it = b.keys(); it.hasNext(); ) {
            String k = it.next();
            out.put(k, Math.max(out.optLong(k, 0), b.optLong(k, 0)));
        }
        return out;
    }
}
