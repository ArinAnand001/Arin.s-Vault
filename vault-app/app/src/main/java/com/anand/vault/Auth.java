package com.anand.vault;

import android.accounts.Account;
import android.content.Context;
import android.os.SystemClock;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.ClearTokenRequest;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Google account access. The app asks for three things only: the user's name/email, and
 * its own private folder in their Drive ("drive.appdata"), which can't see any other files.
 */
final class Auth {
    static final String DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata";
    static final String EMAIL = "https://www.googleapis.com/auth/userinfo.email";
    static final String PROFILE = "https://www.googleapis.com/auth/userinfo.profile";

    /** The user has to tap through Google's screen again (for example, access was removed). */
    static final class NeedsUserException extends Exception {
        NeedsUserException() {
            super("Google account needs to be reconnected");
        }
    }

    private static String cached;
    private static long cachedAt;

    private Auth() {}

    static List<Scope> scopes() {
        return Arrays.asList(new Scope(DRIVE_APPDATA), new Scope(EMAIL), new Scope(PROFILE));
    }

    /** chooseAccount=true always shows Google's account picker. */
    static AuthorizationRequest request(String email, boolean chooseAccount) {
        AuthorizationRequest.Builder b = AuthorizationRequest.builder().setRequestedScopes(scopes());
        if (chooseAccount) b.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT);
        else if (email != null && !email.isEmpty()) b.setAccount(new Account(email, "com.google"));
        return b.build();
    }

    static synchronized void remember(String token) {
        cached = token;
        cachedAt = SystemClock.elapsedRealtime();
    }

    /** Gets a Drive token without showing anything. Must run on a background thread. */
    static String token(Context c) throws Exception {
        synchronized (Auth.class) {
            if (cached != null && SystemClock.elapsedRealtime() - cachedAt < 40 * 60 * 1000L) return cached;
        }
        String email = Vault.get(c).email();
        if (email.isEmpty()) throw new NeedsUserException();
        AuthorizationResult r;
        try {
            r = Tasks.await(Identity.getAuthorizationClient(c).authorize(request(email, false)), 60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ApiException) {
                int code = ((ApiException) cause).getStatusCode();
                if (code == 4 || code == 16 || code == 12501) throw new NeedsUserException();
                throw (ApiException) cause;
            }
            throw e;
        }
        if (r.hasResolution()) throw new NeedsUserException();
        String t = r.getAccessToken();
        if (t == null || t.isEmpty()) throw new NeedsUserException();
        remember(t);
        return t;
    }

    /** Throws away a token Google rejected, so the next call fetches a fresh one. */
    static void invalidate(Context c, String token) {
        synchronized (Auth.class) {
            cached = null;
        }
        if (token == null) return;
        try {
            Tasks.await(Identity.getAuthorizationClient(c)
                    .clearToken(ClearTokenRequest.builder().setToken(token).build()), 20, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    static synchronized void forget() {
        cached = null;
    }

    static JSONObject userInfo(String token) throws IOException, JSONException {
        return new JSONObject(Http.getText("https://www.googleapis.com/oauth2/v3/userinfo", token));
    }

    /** Turns an error into a sentence for the user. */
    static String describe(Throwable e) {
        while (e instanceof ExecutionException && e.getCause() != null) e = e.getCause();
        if (e instanceof ApiException) {
            int code = ((ApiException) e).getStatusCode();
            switch (code) {
                case 10:
                    return "Google rejected this app's signing identity. In Google Cloud > Google Auth Platform > Clients, register an Android OAuth client with package name com.anand.vault and the SHA-1 fingerprint of the certificate that signed this APK. Make sure the client is in the same Cloud project as the Drive API, then try again.";
                case 7:
                    return "No internet connection. Check your connection and try again.";
                case 16:
                case 12501:
                    return "Sign-in was cancelled.";
                case 8:
                    return "Google had a problem. Please try again.";
                default:
                    return "Google sign-in didn't work (code " + code + "). Please try again.";
            }
        }
        if (e instanceof NeedsUserException) return "Reconnect your Google account to continue backing up.";
        if (e instanceof Http.HttpError) {
            Http.HttpError h = (Http.HttpError) e;
            if (h.code == 403 && (h.body.contains("accessNotConfigured") || h.body.contains("has not been used")
                    || h.body.contains("SERVICE_DISABLED"))) {
                return "The Google Drive API is turned off for this app. Turn it on in Google Cloud (setup step 2).";
            }
            if (h.code == 403 && (h.body.contains("insufficientPermissions") || h.body.contains("ACCESS_TOKEN_SCOPE_INSUFFICIENT"))) {
                return "Backup isn't allowed to use its Drive folder. Sign out, sign in again and keep the Drive box ticked.";
            }
            if (h.code == 403 && h.body.contains("storageQuotaExceeded")) {
                return "Your Google storage is full, so backup has stopped. Free up space in your Google account.";
            }
            if (h.code == 401) return "Google sign-in expired. Reconnect your account.";
            return "Google Drive error " + h.code + ". It will try again later.";
        }
        if (e instanceof UnknownHostException) return "No internet connection.";
        if (e instanceof IOException) {
            String m = e.getMessage();
            return m == null || m.isEmpty() ? "Network problem. It will try again later." : m;
        }
        String m = e.getMessage();
        return m == null || m.isEmpty() ? "Something went wrong." : m;
    }

    static boolean isCancel(Throwable e) {
        while (e instanceof ExecutionException && e.getCause() != null) e = e.getCause();
        if (e instanceof ApiException) {
            int code = ((ApiException) e).getStatusCode();
            return code == 16 || code == 12501;
        }
        return false;
    }
}
