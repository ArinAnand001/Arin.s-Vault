package com.anand.vault;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * App updates and the owner's messages, both read from the GitHub repository that builds the app:
 * - version.json is attached to every release (newest build = newest version)
 * - notifications.json in the repo holds the messages (the "Send a notification" workflow adds to it)
 */
final class Updates {
    static final String CH_UPDATES = "updates";
    static final String CH_MESSAGES = "messages";

    private Updates() {}

    static boolean configured() {
        return !BuildConfig.REPO.isEmpty();
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("notices", Context.MODE_PRIVATE);
    }

    /** Checks for new messages and a new version. Runs on a background thread. */
    static void refresh(Context c, boolean notify) {
        if (!configured()) return;
        try {
            fetchMessages(c, notify);
        } catch (Exception ignored) {
        }
        try {
            fetchVersion(c, notify);
        } catch (Exception ignored) {
        }
        Events.emit("notices", Events.obj("unread", unread(c)));
    }

    static void fetchMessages(Context c, boolean notify) throws IOException, JSONException {
        String url = "https://raw.githubusercontent.com/" + BuildConfig.REPO + "/" + BuildConfig.BRANCH
                + "/notifications.json?t=" + (System.currentTimeMillis() / 60_000);
        String text;
        try {
            text = Http.getText(url);
        } catch (Http.HttpError e) {
            if (e.code != 404) throw e;
            text = "{\"messages\":[]}";
        }
        JSONArray msgs = new JSONObject(text).optJSONArray("messages");
        if (msgs == null) msgs = new JSONArray();
        SharedPreferences sp = prefs(c);
        Set<String> seen = new HashSet<>(sp.getStringSet("seen", new HashSet<>()));
        boolean first = !sp.getBoolean("fetched", false);
        JSONArray clean = new JSONArray();
        for (int i = 0; i < msgs.length() && clean.length() < 50; i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            String id = m.optString("id", "");
            if (id.isEmpty()) continue;
            String title = m.optString("title", "");
            String body = m.optString("body", m.optString("message", ""));
            clean.put(Events.obj("id", id, "title", title, "body", body, "date", m.optString("date", "")));
            if (seen.add(id) && !first && notify) {
                post(c, CH_MESSAGES, 1000 + (id.hashCode() & 0xffff), title.isEmpty() ? BuildConfig.APP_NAME : title, body);
            }
        }
        sp.edit().putString("messages", clean.toString()).putStringSet("seen", seen).putBoolean("fetched", true).apply();
    }

    static void fetchVersion(Context c, boolean notify) throws IOException, JSONException {
        String url = "https://github.com/" + BuildConfig.REPO + "/releases/latest/download/version.json";
        JSONObject v = new JSONObject(Http.getText(url));
        int code = v.optInt("versionCode", 0);
        SharedPreferences sp = prefs(c);
        sp.edit().putString("latest", v.toString()).apply();
        if (code > BuildConfig.VERSION_CODE && sp.getInt("notifiedCode", 0) < code) {
            sp.edit().putInt("notifiedCode", code).apply();
            if (notify) {
                post(c, CH_UPDATES, 1, "Update available",
                        "Version " + v.optString("versionName", "") + " of " + BuildConfig.APP_NAME + " is ready. Tap to update.");
            }
        }
    }

    static JSONObject latest(Context c) {
        try {
            JSONObject v = new JSONObject(prefs(c).getString("latest", "{}"));
            return v.optInt("versionCode", 0) > BuildConfig.VERSION_CODE ? v : null;
        } catch (JSONException e) {
            return null;
        }
    }

    /** Everything for the bell screen: the update (if any) first, then messages, newest first. */
    static JSONObject list(Context c) {
        SharedPreferences sp = prefs(c);
        Set<String> read = sp.getStringSet("read", new HashSet<>());
        JSONArray out = new JSONArray();
        int unread = 0;
        JSONObject v = latest(c);
        if (v != null) {
            String id = "update-" + v.optInt("versionCode");
            boolean r = read.contains(id);
            if (!r) unread++;
            out.put(Events.obj("id", id, "kind", "update", "title", "New version available",
                    "body", "Version " + v.optString("versionName") + " is ready to install."
                            + (v.optString("notes", "").isEmpty() ? "" : "\n" + v.optString("notes")),
                    "date", v.optString("date", ""), "read", r, "versionName", v.optString("versionName")));
        }
        try {
            JSONArray msgs = new JSONArray(sp.getString("messages", "[]"));
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.getJSONObject(i);
                boolean r = read.contains(m.optString("id"));
                if (!r) unread++;
                m.put("kind", "message");
                m.put("read", r);
                out.put(m);
            }
        } catch (JSONException ignored) {
        }
        return Events.obj("items", out, "unread", unread, "configured", configured());
    }

    static int unread(Context c) {
        return list(c).optInt("unread", 0);
    }

    static void markAllRead(Context c) {
        JSONArray items = list(c).optJSONArray("items");
        Set<String> read = new HashSet<>(prefs(c).getStringSet("read", new HashSet<>()));
        for (int i = 0; items != null && i < items.length(); i++) read.add(items.optJSONObject(i).optString("id"));
        prefs(c).edit().putStringSet("read", read).apply();
    }

    /** Downloads the newest APK into the app's cache and checks it really is this app. */
    static File downloadApk(Context c, Http.Progress p) throws Exception {
        JSONObject v = latest(c);
        String apk = v == null ? BuildConfig.APP_NAME + ".apk" : v.optString("apk", BuildConfig.APP_NAME + ".apk");
        String url = "https://github.com/" + BuildConfig.REPO + "/releases/latest/download/" + Drive.enc(apk).replace("+", "%20");
        File dir = new File(c.getCacheDir(), "updates");
        Vault.deleteTree(dir);
        dir.mkdirs();
        File out = new File(dir, "update.apk");
        Http.download(url, null, out, v == null ? 0 : v.optLong("size", 0), p);
        PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(out.getPath(), 0);
        if (pi == null || !c.getPackageName().equals(pi.packageName)) {
            out.delete();
            throw new IOException("The downloaded file isn't a valid update.");
        }
        return out;
    }

    static void ensureChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CH_UPDATES, "App updates", NotificationManager.IMPORTANCE_DEFAULT));
        nm.createNotificationChannel(new NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH));
    }

    static void post(Context c, String channel, int id, String title, String body) {
        ensureChannels(c);
        NotificationManagerCompat nm = NotificationManagerCompat.from(c);
        if (!nm.areNotificationsEnabled()) return;
        Intent open = new Intent(c, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("open", "notices");
        PendingIntent pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_stat_vault)
                .setColor(0xFFB69A68)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        try {
            nm.notify(id, n);
        } catch (SecurityException ignored) {
        }
    }
}
