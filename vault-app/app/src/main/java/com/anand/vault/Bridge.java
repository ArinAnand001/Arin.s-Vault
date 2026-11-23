package com.anand.vault;

import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/**
 * The functions the page (index.html) can call as window.VaultNative.*.
 * Quick ones return a JSON string; slow ones take a callback id and answer through
 * window.__vaultCb(id, result).
 */
final class Bridge {
    private final MainActivity a;
    private final Vault v;

    Bridge(MainActivity a) {
        this.a = a;
        this.v = Vault.get(a);
    }

    private static String err(Exception e) {
        return Events.obj("ok", false, "error", e.getMessage() == null ? "Something went wrong" : e.getMessage()).toString();
    }

    private void changed() {
        Sync.get(a).request();
    }

    // ---------------------------------------------------------------- state

    @JavascriptInterface
    public String state() {
        JSONObject s = v.state();
        try {
            s.put("notificationsOn", a.notificationsOn());
        } catch (Exception ignored) {
        }
        return s.toString();
    }

    @JavascriptInterface
    public String library() {
        try {
            return v.forJs().toString();
        } catch (Exception e) {
            return "{\"items\":[],\"albums\":[]}";
        }
    }

    @JavascriptInterface
    public String storage() {
        return v.storage().toString();
    }

    @JavascriptInterface
    public String syncStatus() {
        return Sync.get(a).status().toString();
    }

    @JavascriptInterface
    public String takeOpen() {
        return a.takeOpen();
    }

    @JavascriptInterface
    public void setSetting(String key, String value) {
        v.setSetting(key, value);
        if ("secure".equals(key)) a.runOnUiThread(a::applySecure);
        if ("wifiOnly".equals(key)) changed();
    }

    // ---------------------------------------------------------------- account & PIN

    @JavascriptInterface
    public void continueAsGuest() {
        v.setGuest();
    }

    @JavascriptInterface
    public String setPin(String pin) {
        try {
            v.setPin(pin);
            return "{\"ok\":true}";
        } catch (Exception e) {
            return err(e);
        }
    }

    @JavascriptInterface
    public String checkPin(String pin) {
        return v.checkPin(pin).toString();
    }

    @JavascriptInterface
    public void signIn(String cb) {
        a.signIn(cb, "signin");
    }

    @JavascriptInterface
    public void reconnect(String cb) {
        a.signIn(cb, "reconnect");
    }

    @JavascriptInterface
    public void verifyOwner(String cb) {
        a.verifyOwner(cb);
    }

    @JavascriptInterface
    public void signOut(String cb) {
        a.signOut(cb);
    }

    @JavascriptInterface
    public void resetApp(String cb) {
        a.signOut(cb);
    }

    // ---------------------------------------------------------------- photos, videos, albums

    @JavascriptInterface
    public void pick(String cb, String albumId) {
        a.pick(cb, albumId == null || albumId.isEmpty() ? null : albumId);
    }

    @JavascriptInterface
    public String createAlbum(String name) {
        try {
            String r = v.createAlbum(name).toString();
            changed();
            return r;
        } catch (Exception e) {
            return err(e);
        }
    }

    @JavascriptInterface
    public String renameAlbum(String id, String name) {
        try {
            v.renameAlbum(id, name);
            changed();
            return "{\"ok\":true}";
        } catch (Exception e) {
            return err(e);
        }
    }

    @JavascriptInterface
    public String deleteAlbum(String id) {
        try {
            v.deleteAlbum(id);
            changed();
            return "{\"ok\":true}";
        } catch (Exception e) {
            return err(e);
        }
    }

    @JavascriptInterface
    public String addToAlbum(String albumId, String idsJson) {
        try {
            v.addToAlbum(albumId, MainActivity.ids(idsJson));
            changed();
            return "{\"ok\":true}";
        } catch (Exception e) {
            return err(e);
        }
    }

    @JavascriptInterface
    public String removeFromAlbum(String albumId, String idsJson) {
        try {
            v.removeFromAlbum(albumId, MainActivity.ids(idsJson));
            changed();
            return "{\"ok\":true}";
        } catch (Exception e) {
            return err(e);
        }
    }

    @JavascriptInterface
    public String trash(String idsJson) {
        try {
            v.trash(MainActivity.ids(idsJson), true);
            changed();
            return "{\"ok\":true}";
        } catch (Exception e) {
            return err(e);
        }
    }

    @JavascriptInterface
    public String restore(String idsJson) {
        try {
            v.trash(MainActivity.ids(idsJson), false);
            changed();
            return "{\"ok\":true}";
        } catch (Exception e) {
            return err(e);
        }
    }

    @JavascriptInterface
    public String deleteForever(String idsJson) {
        try {
            v.deleteForever(MainActivity.ids(idsJson));
            changed();
            return "{\"ok\":true}";
        } catch (Exception e) {
            return err(e);
        }
    }

    @JavascriptInterface
    public String emptyBin() {
        try {
            v.emptyBin();
            changed();
            return "{\"ok\":true}";
        } catch (Exception e) {
            return err(e);
        }
    }

    @JavascriptInterface
    public void ensureLocal(String cb, String id) {
        Sync.get(a).ensureLocal(id, r -> a.callback(cb, r));
    }

    @JavascriptInterface
    public void share(String cb, String idsJson) {
        a.share(cb, MainActivity.ids(idsJson));
    }

    @JavascriptInterface
    public void saveToGallery(String cb, String idsJson) {
        a.saveToGallery(cb, MainActivity.ids(idsJson));
    }

    @JavascriptInterface
    public void openWith(String cb, String id) {
        a.openWith(cb, id);
    }

    // ---------------------------------------------------------------- backup

    @JavascriptInterface
    public void syncNow() {
        Sync.get(a).request();
    }

    @JavascriptInterface
    public void downloadEverything() {
        Sync.get(a).downloadEverything();
    }

    // ---------------------------------------------------------------- notices & updates

    @JavascriptInterface
    public String notices() {
        return Updates.list(a).toString();
    }

    @JavascriptInterface
    public void markNoticesRead() {
        Updates.markAllRead(a);
    }

    @JavascriptInterface
    public void refreshNotices(String cb) {
        new Thread(() -> {
            Updates.refresh(a, false);
            a.callback(cb, Updates.list(a));
        }, "notices").start();
    }

    @JavascriptInterface
    public void installUpdate(String cb) {
        a.installUpdate(cb);
    }

    @JavascriptInterface
    public void requestNotifications() {
        a.requestNotifications();
    }

    // ---------------------------------------------------------------- misc

    @JavascriptInterface
    public void exitApp() {
        a.runOnUiThread(() -> a.moveTaskToBack(true));
    }

    @JavascriptInterface
    public void openUrl(String url) {
        if (url != null && url.startsWith("https://")) a.runOnUiThread(() -> a.openUrl(url));
    }
}
