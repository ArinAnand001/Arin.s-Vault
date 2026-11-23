package com.anand.vault;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.content.IntentCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One screen: a WebView showing assets/www/index.html, plus the phone features it needs. */
public class MainActivity extends ComponentActivity implements Events.Sink {
    static final int BG = 0xFF160B1D;

    WebView web;
    Vault vault;
    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newSingleThreadExecutor();

    private View fullView;
    private WebChromeClient.CustomViewCallback fullCallback;

    private ActivityResultLauncher<PickVisualMediaRequest> pickLauncher;
    private ActivityResultLauncher<IntentSenderRequest> authLauncher;
    private ActivityResultLauncher<Intent> verifyLauncher;
    private ActivityResultLauncher<Intent> unknownSourcesLauncher;
    private ActivityResultLauncher<String> notifLauncher;

    private String pickCb;
    private String pickAlbum;
    private String authCb;
    private String authPurpose;
    private String verifyCb;
    private String installCb;
    private File pendingApk;
    private String pendingOpen;

    private boolean expectExternal;
    private long stoppedAt;
    private long externalAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        vault = Vault.get(this);
        Events.set(this);
        applySecure();

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(false);
        bars.setAppearanceLightNavigationBars(false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        web = new WebView(this);
        web.setBackgroundColor(BG);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(sys.left, sys.top, sys.right, Math.max(sys.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(true);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setHapticFeedbackEnabled(false);
        web.setOnLongClickListener(v -> true);
        web.addJavascriptInterface(new Bridge(this), "VaultNative");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme) || "mailto".equals(scheme)) {
                    openUrl(u.toString());
                    return true;
                }
                return !u.toString().startsWith("file:///android_asset/");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                enterFullScreen(view, callback);
            }

            @Override
            public void onHideCustomView() {
                exitFullScreen();
            }
        });

        pickLauncher = registerForActivityResult(new ActivityResultContracts.PickMultipleVisualMedia(), this::onPicked);
        authLauncher = registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), this::onAuthResult);
        verifyLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::onVerifyResult);
        unknownSourcesLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> onUnknownSourcesResult());
        notifLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                granted -> emit("notifPermission", Events.obj("granted", granted)));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (fullView != null) {
                    exitFullScreen();
                    return;
                }
                web.evaluateJavascript("window.__vaultBack ? window.__vaultBack() : false", value -> {
                    if (!"true".equals(value)) moveTaskToBack(true);
                });
            }
        });

        handleIntent(getIntent());
        web.loadUrl("file:///android_asset/www/index.html");
        Updates.ensureChannels(this);
        VaultWorker.schedule(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Events.set(this);
        long now = SystemClock.elapsedRealtime();
        boolean lock;
        if (expectExternal) {
            expectExternal = false;
            lock = externalAt > 0 && now - externalAt > 10 * 60_000L;
        } else {
            lock = stoppedAt > 0 && now - stoppedAt >= vault.lockDelayMs();
        }
        stoppedAt = 0;
        externalAt = 0;
        if (lock && vault.pinSet()) emit("lock", null);
        new Thread(() -> Updates.refresh(this, false), "notices").start();
        Sync.get(this).request();
    }

    @Override
    protected void onStop() {
        super.onStop();
        long now = SystemClock.elapsedRealtime();
        if (expectExternal) externalAt = now;
        else stoppedAt = now;
        if (!expectExternal) emit("hidden", null);
    }

    @Override
    protected void onDestroy() {
        Events.clear(this);
        if (web != null) {
            web.removeJavascriptInterface("VaultNative");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- talking to the page

    @Override
    public void emit(String name, JSONObject data) {
        js("window.__vaultEvent&&window.__vaultEvent(" + JSONObject.quote(name) + "," + (data == null ? "{}" : data.toString()) + ")");
    }

    void callback(String cbId, JSONObject data) {
        if (cbId == null) return;
        js("window.__vaultCb&&window.__vaultCb(" + JSONObject.quote(cbId) + "," + (data == null ? "{}" : data.toString()) + ")");
    }

    private void js(String code) {
        main.post(() -> {
            if (web != null) web.evaluateJavascript(code, null);
        });
    }

    void toast(String msg) {
        main.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    void applySecure() {
        if (vault.secure()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    String takeOpen() {
        String o = pendingOpen;
        pendingOpen = null;
        return o == null ? "" : o;
    }

    void openUrl(String url) {
        try {
            expectExternal = true;
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            expectExternal = false;
        }
    }

    // ---------------------------------------------------------------- full-screen video

    private void enterFullScreen(View view, WebChromeClient.CustomViewCallback callback) {
        if (fullView != null) {
            callback.onCustomViewHidden();
            return;
        }
        fullView = view;
        fullCallback = callback;
        view.setBackgroundColor(Color.BLACK);
        ((FrameLayout) getWindow().getDecorView()).addView(view,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(WindowInsetsCompat.Type.systemBars());
    }

    private void exitFullScreen() {
        if (fullView == null) return;
        ((FrameLayout) getWindow().getDecorView()).removeView(fullView);
        fullView = null;
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).show(WindowInsetsCompat.Type.systemBars());
        WebChromeClient.CustomViewCallback cb = fullCallback;
        fullCallback = null;
        if (cb != null) cb.onCustomViewHidden();
    }

    // ---------------------------------------------------------------- adding photos & videos

    void pick(String cbId, String albumId) {
        main.post(() -> {
            pickCb = cbId;
            pickAlbum = albumId;
            expectExternal = true;
            try {
                pickLauncher.launch(new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo.INSTANCE)
                        .build());
            } catch (Exception e) {
                expectExternal = false;
                pickCb = null;
                callback(cbId, Events.obj("ok", false, "error", "Couldn't open the photo picker."));
            }
        });
    }

    private void onPicked(List<Uri> uris) {
        String cb = pickCb;
        String album = pickAlbum;
        pickCb = null;
        pickAlbum = null;
        if (uris == null || uris.isEmpty()) {
            callback(cb, Events.obj("ok", true, "added", 0));
            return;
        }
        runImport(new ArrayList<>(uris), album, cb);
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        String open = i.getStringExtra("open");
        if (open != null) {
            pendingOpen = open;
            i.removeExtra("open");
            emit("open", Events.obj("screen", open));
        }
        String action = i.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;
        ArrayList<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri.class);
            if (u != null) uris.add(u);
        } else {
            ArrayList<Uri> list = IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri.class);
            if (list != null) uris.addAll(list);
        }
        ClipData clip = i.getClipData();
        if (uris.isEmpty() && clip != null) {
            for (int k = 0; k < clip.getItemCount(); k++) {
                Uri u = clip.getItemAt(k).getUri();
                if (u != null) uris.add(u);
            }
        }
        i.setAction(Intent.ACTION_MAIN);
        if (uris.isEmpty()) return;
        if (vault.mode().isEmpty()) {
            toast("Open " + BuildConfig.APP_NAME + " and finish setting it up first, then share again.");
            return;
        }
        runImport(uris, null, null);
    }

    private void runImport(List<Uri> uris, String albumId, String cbId) {
        final int total = uris.size();
        emit("import", Events.obj("index", 0, "total", total, "pct", 0));
        io.execute(() -> {
            int ok = 0;
            int failed = 0;
            String lastError = null;
            for (int k = 0; k < total; k++) {
                final int index = k;
                try {
                    vault.importUri(uris.get(k), albumId, (done, size) ->
                            emit("import", Events.obj("index", index, "total", total,
                                    "pct", size > 0 ? (int) Math.min(100, done * 100 / size) : -1)));
                    ok++;
                } catch (Exception e) {
                    failed++;
                    lastError = e.getMessage();
                }
                if (k % 5 == 4) emit("library", null);
            }
            emit("import", Events.obj("index", total, "total", total, "pct", 100, "finished", true));
            emit("library", null);
            JSONObject res = Events.obj("ok", true, "added", ok, "failed", failed, "error", lastError);
            if (cbId != null) callback(cbId, res);
            else emit("shared", res);
            Sync.get(this).request();
        });
    }

    // ---------------------------------------------------------------- Google account

    /** purpose: "signin" (pick an account), "verify" (forgot PIN), "reconnect" (same account again). */
    void signIn(String cbId, String purpose) {
        main.post(() -> {
            authCb = cbId;
            authPurpose = purpose;
            boolean reconnect = "reconnect".equals(purpose);
            Identity.getAuthorizationClient(this)
                    .authorize(Auth.request(reconnect ? vault.email() : null, !reconnect))
                    .addOnSuccessListener(r -> {
                        if (r.hasResolution() && r.getPendingIntent() != null) {
                            try {
                                expectExternal = true;
                                authLauncher.launch(new IntentSenderRequest.Builder(r.getPendingIntent().getIntentSender()).build());
                            } catch (Exception e) {
                                expectExternal = false;
                                authDone(null, e);
                            }
                        } else {
                            authDone(r, null);
                        }
                    })
                    .addOnFailureListener(e -> authDone(null, e));
        });
    }

    private void onAuthResult(ActivityResult res) {
        if (res.getData() == null) {
            authDone(null, null);
            return;
        }
        try {
            AuthorizationResult r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.getData());
            authDone(r, null);
        } catch (ApiException e) {
            authDone(null, e);
        }
    }

    private void authDone(AuthorizationResult r, Exception e) {
        String cb = authCb;
        String purpose = authPurpose;
        authCb = null;
        authPurpose = null;
        if (cb == null) return;
        if (r == null || r.getAccessToken() == null) {
            boolean cancelled = e == null || Auth.isCancel(e);
            callback(cb, Events.obj("ok", false, "cancelled", cancelled, "error", cancelled ? null : Auth.describe(e)));
            return;
        }
        String token = r.getAccessToken();
        List<String> granted = r.getGrantedScopes();
        if (!"verify".equals(purpose) && granted != null && !granted.isEmpty() && !granted.contains(Auth.DRIVE_APPDATA)) {
            callback(cb, Events.obj("ok", false, "error",
                    "Backup needs permission to use " + BuildConfig.APP_NAME + "'s own Google Drive folder. Try again and keep that box ticked."));
            return;
        }
        io.execute(() -> {
            try {
                JSONObject u = Auth.userInfo(token);
                String email = u.optString("email", "");
                if (email.isEmpty()) throw new Exception("Google didn't share the account's email address.");
                if ("verify".equals(purpose)) {
                    boolean same = email.equalsIgnoreCase(vault.email());
                    callback(cb, Events.obj("ok", same, "error", same ? null : "That's a different account. Choose " + vault.email() + "."));
                    return;
                }
                if ("reconnect".equals(purpose)) {
                    if (!email.equalsIgnoreCase(vault.email())) throw new Exception("That's a different account. Choose " + vault.email() + ".");
                    Auth.remember(token);
                    callback(cb, Events.obj("ok", true, "email", email));
                    Sync.get(this).request();
                    return;
                }
                boolean switching = vault.isGoogle() && !email.equalsIgnoreCase(vault.email());
                if (switching) throw new Exception("Sign out first to switch to a different account.");
                vault.setGoogle(email, u.optString("name", ""), u.optString("picture", ""));
                Auth.remember(token);
                callback(cb, Events.obj("ok", true, "email", email, "name", u.optString("name", "")));
                Sync.get(this).request();
            } catch (Exception ex) {
                callback(cb, Events.obj("ok", false, "error", Auth.describe(ex)));
            }
        });
    }

    void signOut(String cbId) {
        io.execute(() -> {
            Auth.forget();
            vault.wipe();
            callback(cbId, Events.obj("ok", true));
        });
    }

    // ---------------------------------------------------------------- forgot PIN

    void verifyOwner(String cbId) {
        main.post(() -> {
            KeyguardManager km = getSystemService(KeyguardManager.class);
            if (km != null && km.isDeviceSecure()) {
                Intent i = km.createConfirmDeviceCredentialIntent("Verify it's you",
                        "Use your phone's screen lock to reset your " + BuildConfig.APP_NAME + " PIN");
                if (i != null) {
                    verifyCb = cbId;
                    expectExternal = true;
                    verifyLauncher.launch(i);
                    return;
                }
            }
            if (vault.isGoogle()) {
                signIn(cbId, "verify");
                return;
            }
            callback(cbId, Events.obj("ok", false, "noLock", true));
        });
    }

    private void onVerifyResult(ActivityResult r) {
        String cb = verifyCb;
        verifyCb = null;
        callback(cb, Events.obj("ok", r.getResultCode() == Activity.RESULT_OK));
    }

    // ---------------------------------------------------------------- notifications permission

    void requestNotifications() {
        main.post(() -> {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                if (vault.prefs.getBoolean("notifAskedSystem", false) && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    openNotificationSettings();
                } else {
                    vault.prefs.edit().putBoolean("notifAskedSystem", true).apply();
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                }
            } else if (!androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                openNotificationSettings();
            } else {
                emit("notifPermission", Events.obj("granted", true));
            }
        });
    }

    private void openNotificationSettings() {
        try {
            expectExternal = true;
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        } catch (Exception e) {
            expectExternal = false;
        }
    }

    boolean notificationsOn() {
        return androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled();
    }

    // ---------------------------------------------------------------- app update

    void installUpdate(String cbId) {
        io.execute(() -> {
            try {
                File apk = Updates.downloadApk(this, (done, total) ->
                        emit("update", Events.obj("pct", total > 0 ? (int) (done * 100 / total) : -1)));
                main.post(() -> startInstall(apk, cbId));
            } catch (Exception e) {
                callback(cbId, Events.obj("ok", false, "error", "Couldn't download the update. " + Auth.describe(e)));
            }
        });
    }

    private void startInstall(File apk, String cbId) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            pendingApk = apk;
            installCb = cbId;
            try {
                expectExternal = true;
                unknownSourcesLauncher.launch(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
                toast("Turn on \"Allow from this source\", then go back");
            } catch (Exception e) {
                expectExternal = false;
                pendingApk = null;
                installCb = null;
                launchInstaller(apk, cbId);
            }
            return;
        }
        launchInstaller(apk, cbId);
    }

    private void onUnknownSourcesResult() {
        File apk = pendingApk;
        String cb = installCb;
        pendingApk = null;
        installCb = null;
        if (apk == null) return;
        if (getPackageManager().canRequestPackageInstalls()) launchInstaller(apk, cb);
        else callback(cb, Events.obj("ok", false, "error",
                "To update from inside the app, allow \"Install unknown apps\" for " + BuildConfig.APP_NAME + "."));
    }

    private void launchInstaller(File apk, String cbId) {
        try {
            Uri u = FileProvider.getUriForFile(this, getPackageName() + ".files", apk);
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(u, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            expectExternal = true;
            startActivity(i);
            callback(cbId, Events.obj("ok", true));
        } catch (Exception e) {
            expectExternal = false;
            callback(cbId, Events.obj("ok", false, "error", "Couldn't open the installer."));
        }
    }

    // ---------------------------------------------------------------- share, save, open with

    private Uri shareUri(File f) {
        return FileProvider.getUriForFile(this, getPackageName() + ".files", f);
    }

    void share(String cbId, List<String> ids) {
        main.post(() -> {
            ArrayList<Uri> uris = new ArrayList<>();
            String type = null;
            for (String id : ids) {
                JSONObject it = vault.itemCopy(id);
                if (it == null) continue;
                File f = vault.original(it);
                if (!f.exists()) continue;
                uris.add(shareUri(f));
                String m = it.optString("mime", "*/*");
                if (type == null) type = m;
                else if (!type.equals(m)) {
                    String a = type.split("/")[0];
                    type = a.equals(m.split("/")[0]) ? a + "/*" : "*/*";
                }
            }
            if (uris.isEmpty()) {
                callback(cbId, Events.obj("ok", false, "error", "Nothing to share yet."));
                return;
            }
            Intent i;
            if (uris.size() == 1) {
                i = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                i = new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            i.setType(type);
            ClipData clip = ClipData.newRawUri("", uris.get(0));
            for (int k = 1; k < uris.size(); k++) clip.addItem(new ClipData.Item(uris.get(k)));
            i.setClipData(clip);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                expectExternal = true;
                startActivity(Intent.createChooser(i, null));
                callback(cbId, Events.obj("ok", true));
            } catch (Exception e) {
                expectExternal = false;
                callback(cbId, Events.obj("ok", false, "error", "No app can share this."));
            }
        });
    }

    void openWith(String cbId, String id) {
        main.post(() -> {
            JSONObject it = vault.itemCopy(id);
            File f = it == null ? null : vault.original(it);
            if (f == null || !f.exists()) {
                callback(cbId, Events.obj("ok", false, "error", "This file isn't on the phone yet."));
                return;
            }
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(shareUri(f), it.optString("mime", "*/*"))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                expectExternal = true;
                startActivity(Intent.createChooser(i, null));
                callback(cbId, Events.obj("ok", true));
            } catch (Exception e) {
                expectExternal = false;
                callback(cbId, Events.obj("ok", false, "error", "No app on this phone can open it."));
            }
        });
    }

    /** Copies items out of the vault into the phone's Gallery (Pictures/Vault, Movies/Vault). Android 10+. */
    void saveToGallery(String cbId, List<String> ids) {
        io.execute(() -> {
            if (Build.VERSION.SDK_INT < 29) {
                callback(cbId, Events.obj("ok", false, "error", "Use Share to save on this Android version."));
                return;
            }
            ContentResolver cr = getContentResolver();
            int saved = 0;
            for (String id : ids) {
                JSONObject it = vault.itemCopy(id);
                if (it == null) continue;
                File f = vault.original(it);
                if (!f.exists()) continue;
                boolean video = "video".equals(it.optString("type"));
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, it.optString("name", f.getName()));
                v.put(MediaStore.MediaColumns.MIME_TYPE, it.optString("mime"));
                v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/" + BuildConfig.APP_NAME);
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri coll = video ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri dest = null;
                try {
                    dest = cr.insert(coll, v);
                    if (dest == null) continue;
                    try (InputStream in = new FileInputStream(f); OutputStream out = cr.openOutputStream(dest)) {
                        if (out == null) throw new java.io.IOException("Can't write");
                        Http.copy(in, out, f.length(), null);
                    }
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    cr.update(dest, done, null, null);
                    saved++;
                } catch (Exception e) {
                    if (dest != null) {
                        try {
                            cr.delete(dest, null, null);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            callback(cbId, Events.obj("ok", saved > 0, "saved", saved,
                    "error", saved > 0 ? null : "Couldn't save to the Gallery."));
        });
    }

    static List<String> ids(String json) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }
}
