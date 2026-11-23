package com.anand.vault;

import android.content.Context;
import android.net.ConnectivityManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps a Google account's vault backed up in its Drive app folder:
 * merges the backup's table of contents, uploads new photos/videos, removes deleted ones.
 */
final class Sync {
    static final String MANIFEST = "vault-manifest.json";

    interface Done {
        void done(JSONObject result);
    }

    private static Sync instance;

    static synchronized Sync get(Context c) {
        if (instance == null) instance = new Sync(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Vault vault;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final ExecutorService fetchExec = Executors.newSingleThreadExecutor();
    private final AtomicBoolean queued = new AtomicBoolean(false);
    private volatile JSONObject status = Events.obj("state", "idle");
    private volatile boolean pullAll;
    private volatile boolean stopRequested;

    private Sync(Context c) {
        ctx = c;
        vault = Vault.get(c);
    }

    JSONObject status() {
        JSONObject s;
        try {
            s = new JSONObject(status.toString());
        } catch (JSONException e) {
            s = new JSONObject();
        }
        int[] counts = vault.backupCounts();
        try {
            s.put("total", counts[0]);
            s.put("backedUp", counts[1]);
            s.put("last", vault.prefs.getLong("lastSync", 0));
            s.put("pullAll", pullAll);
        } catch (JSONException ignored) {
        }
        return s;
    }

    private void setStatus(String state, String msg) {
        status = Events.obj("state", state, "msg", msg);
        Events.emit("sync", status());
    }

    /** Starts a backup run soon (several requests in a row only cause one extra run). */
    void request() {
        if (!vault.isGoogle()) return;
        if (queued.compareAndSet(false, true)) {
            exec.submit(() -> {
                queued.set(false);
                runSafely();
            });
        }
    }

    /** For the background worker: runs a backup and waits up to 9 minutes for it. */
    void runAndWait() {
        if (!vault.isGoogle()) return;
        stopRequested = false;
        Future<?> f = exec.submit(this::runSafely);
        try {
            f.get(9, TimeUnit.MINUTES);
        } catch (Exception e) {
            stopRequested = true;
        }
    }

    void stop() {
        stopRequested = true;
    }

    void downloadEverything() {
        pullAll = true;
        request();
    }

    private void runSafely() {
        if (!vault.isGoogle()) return;
        try {
            run();
        } catch (Auth.NeedsUserException e) {
            setStatus("signin", "Backup paused. Tap to reconnect your Google account.");
        } catch (Throwable t) {
            setStatus("error", Auth.describe(t));
        } finally {
            stopRequested = false;
        }
    }

    private void run() throws Exception {
        try {
            vault.purgeOldBin();
        } catch (Exception ignored) {
        }
        setStatus("running", "Checking your backup…");
        String token = Auth.token(ctx);
        try {
            sync(new Drive(token));
        } catch (Http.HttpError e) {
            if (e.code != 401) throw e;
            Auth.invalidate(ctx, token);
            sync(new Drive(Auth.token(ctx)));
        }
    }

    private void sync(Drive d) throws Exception {
        String accountAtStart = vault.email();

        // 1. What's in the backup right now
        List<JSONObject> files = d.listAll();
        Map<String, JSONObject> byName = new HashMap<>();
        List<JSONObject> manifests = new ArrayList<>();
        for (JSONObject f : files) {
            String name = f.optString("name");
            if (MANIFEST.equals(name)) manifests.add(f);
            else byName.put(name, f);
        }
        manifests.sort(Comparator.comparing((JSONObject f) -> f.optString("modifiedTime")));

        // 2. Bring in changes made on other phones (or restore after a reinstall)
        boolean changed = false;
        List<JSONObject> readable = new ArrayList<>();
        for (JSONObject m : manifests) {
            try {
                if (vault.mergeRemote(new JSONObject(d.downloadText(m.getString("id"))))) changed = true;
                readable.add(m);
            } catch (JSONException ignored) {
                // A damaged copy: leave it alone (never delete what couldn't be read).
            }
            if (!accountAtStart.equals(vault.email())) return;
        }
        manifests = readable;
        for (JSONObject it : vault.itemsCopy()) {
            String id = it.optString("id");
            String driveId = it.optString("driveId", "");
            String thumbId = it.optString("thumbId", "");
            JSONObject mf = byName.get(mediaName(it));
            JSONObject tf = byName.get(thumbName(id));
            String newDrive = driveId.isEmpty() && mf != null ? mf.optString("id") : null;
            String newThumb = thumbId.isEmpty() && tf != null ? tf.optString("id") : null;
            if (newDrive != null || newThumb != null) vault.setDriveIds(id, newDrive, newThumb);
        }
        if (changed) Events.emit("library", null);

        // 3. Remove files of photos/videos deleted for good
        for (JSONObject f : byName.values()) {
            String id = idFromName(f.optString("name"));
            if (id != null && vault.isGone(id)) d.delete(f.optString("id"));
        }

        // 4. Thumbnails for restored items, so the grid fills in quickly
        int got = 0;
        for (JSONObject it : vault.itemsCopy()) {
            String id = it.optString("id");
            String thumbId = it.optString("thumbId", "");
            if (thumbId.isEmpty() || vault.thumb(id).exists()) continue;
            if (stopRequested || !accountAtStart.equals(vault.email())) return;
            setStatus("running", "Restoring your vault…");
            try {
                d.download(thumbId, vault.thumb(id), 0, null);
                if (++got % 12 == 0) Events.emit("library", null);
            } catch (Http.HttpError e) {
                if (e.code == 401) throw e;
            }
        }
        if (got > 0) Events.emit("library", null);

        // 5. Upload new photos and videos
        boolean metered = isMetered();
        boolean waitingForWifi = false;
        List<JSONObject> todo = new ArrayList<>();
        for (JSONObject it : vault.itemsCopy()) {
            if (it.optString("driveId", "").isEmpty() && vault.original(it).exists()) todo.add(it);
        }
        if (!todo.isEmpty() && vault.wifiOnly() && metered) {
            waitingForWifi = true;
        } else {
            int n = 0;
            for (JSONObject it : todo) {
                if (stopRequested || !accountAtStart.equals(vault.email())) return;
                n++;
                upload(d, it, n, todo.size());
                // write the table of contents now and then, so a long upload run isn't lost
                if (n % 25 == 0) writeManifest(d, manifests);
            }
        }

        // 6. "Download everything to this phone"
        if (pullAll && !(vault.wifiOnly() && metered)) {
            List<JSONObject> missing = new ArrayList<>();
            for (JSONObject it : vault.itemsCopy()) {
                if (!vault.original(it).exists() && !it.optString("driveId", "").isEmpty()) missing.add(it);
            }
            int n = 0;
            for (JSONObject it : missing) {
                if (stopRequested || !accountAtStart.equals(vault.email())) return;
                n++;
                final int index = n;
                final String label = "Downloading " + index + " of " + missing.size();
                setStatus("running", label);
                fetch(d, it, (done, total) -> {
                    status = Events.obj("state", "running",
                            "msg", label + (total > 0 ? " · " + (done * 100 / total) + "%" : ""));
                    Events.emit("sync", status());
                });
                Events.emit("library", null);
            }
            pullAll = false;
        }

        // 7. Save the table of contents
        writeManifest(d, manifests);
        vault.prefs.edit().putLong("lastSync", System.currentTimeMillis()).apply();
        if (waitingForWifi) setStatus("wifi", "Waiting for Wi-Fi to back up " + todo.size() + (todo.size() == 1 ? " item" : " items"));
        else setStatus("done", "Backed up");
    }

    private void upload(Drive d, JSONObject it, int n, int total) throws Exception {
        String id = it.optString("id");
        File file = vault.original(it);
        String label = "Backing up " + n + " of " + total;
        setStatus("running", label);
        final String key = "up_" + id;
        String driveId = d.uploadLarge(file, mediaName(it), it.optString("mime", "application/octet-stream"),
                new Drive.SessionStore() {
                    @Override
                    public String get() {
                        return vault.prefs.getString(key, null);
                    }

                    @Override
                    public void put(String session) {
                        if (session == null) vault.prefs.edit().remove(key).apply();
                        else vault.prefs.edit().putString(key, session).apply();
                    }
                },
                (done, size) -> {
                    status = Events.obj("state", "running", "msg", label + (size > 0 ? " · " + (done * 100 / size) + "%" : ""));
                    Events.emit("sync", status());
                },
                () -> stopRequested);
        String thumbId = null;
        File t = vault.thumb(id);
        if (it.optString("thumbId", "").isEmpty() && t.exists()) {
            thumbId = d.createSmall(thumbName(id), "image/jpeg", readBytes(t));
        }
        if (vault.item(id) == null || vault.isGone(id)) {
            // deleted while it was uploading
            d.delete(driveId);
            if (thumbId != null) d.delete(thumbId);
            return;
        }
        vault.setDriveIds(id, driveId, thumbId);
        Events.emit("library", null);
    }

    private void writeManifest(Drive d, List<JSONObject> old) throws Exception {
        String json = vault.manifestJson();
        String hash = sha1(json);
        String lastId = vault.prefs.getString("manifestId", "");
        if (old.size() == 1 && hash.equals(vault.prefs.getString("manifestHash", ""))
                && lastId.equals(old.get(0).optString("id"))) return;
        String newId = d.createSmall(MANIFEST, "application/json", json.getBytes(StandardCharsets.UTF_8));
        for (JSONObject m : old) d.delete(m.optString("id"));
        old.clear();
        JSONObject mine = new JSONObject();
        mine.put("id", newId);
        old.add(mine);
        vault.prefs.edit().putString("manifestId", newId).putString("manifestHash", hash).apply();
    }

    /** Downloads one original from the backup to this phone. */
    private void fetch(Drive d, JSONObject it, Http.Progress p) throws Exception {
        File dest = vault.original(it);
        d.download(it.getString("driveId"), dest, it.optLong("size", 0), p);
        JSONObject live = vault.itemCopy(it.optString("id"));
        if (live != null) {
            try {
                vault.makeDerived(live, dest);
            } catch (Exception ignored) {
            }
        }
    }

    /** Makes sure the original is on this phone before it's opened, shared or saved. */
    void ensureLocal(String id, Done done) {
        fetchExec.submit(() -> {
            try {
                JSONObject it = vault.itemCopy(id);
                if (it == null) {
                    done.done(Events.obj("ok", false, "error", "This item was deleted."));
                    return;
                }
                if (vault.original(it).exists()) {
                    done.done(Events.obj("ok", true));
                    return;
                }
                if (it.optString("driveId", "").isEmpty() || !vault.isGoogle()) {
                    done.done(Events.obj("ok", false, "error", "This item isn't on this phone and has no backup."));
                    return;
                }
                String token = Auth.token(ctx);
                Http.Progress p = (got, total) -> Events.emit("download",
                        Events.obj("id", id, "pct", total > 0 ? (int) (got * 100 / total) : -1));
                try {
                    fetch(new Drive(token), it, p);
                } catch (Http.HttpError e) {
                    if (e.code != 401) throw e;
                    Auth.invalidate(ctx, token);
                    fetch(new Drive(Auth.token(ctx)), it, p);
                }
                Events.emit("library", null);
                done.done(Events.obj("ok", true));
            } catch (Auth.NeedsUserException e) {
                done.done(Events.obj("ok", false, "error", "Reconnect your Google account to open this.", "signin", true));
            } catch (Throwable t) {
                done.done(Events.obj("ok", false, "error", Auth.describe(t)));
            }
        });
    }

    private boolean isMetered() {
        try {
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            return cm != null && cm.isActiveNetworkMetered();
        } catch (Exception e) {
            return false;
        }
    }

    static String mediaName(JSONObject it) {
        return "m_" + it.optString("id") + "." + it.optString("ext", "bin");
    }

    static String thumbName(String id) {
        return "t_" + id + ".jpg";
    }

    /** "m_<id>.jpg" or "t_<id>.jpg" → id. */
    static String idFromName(String name) {
        if (name == null || name.length() < 3 || name.charAt(1) != '_') return null;
        char k = name.charAt(0);
        if (k != 'm' && k != 't') return null;
        int dot = name.lastIndexOf('.');
        if (dot <= 2) return null;
        return name.substring(2, dot);
    }

    static byte[] readBytes(File f) throws java.io.IOException {
        return java.nio.file.Files.readAllBytes(f.toPath());
    }

    static String sha1(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
