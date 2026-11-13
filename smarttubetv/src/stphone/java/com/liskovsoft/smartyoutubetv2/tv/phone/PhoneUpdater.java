package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.liskovsoft.smartyoutubetv2.tv.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Updates from this project's GitHub releases: finds the latest build, downloads its APK straight into Android's
 * package installer, and (optionally) checks quietly in the background. The screen is {@link PhoneUpdateActivity}.
 *
 * Release tags are build-N and the app's version code is BASE_VERSION_CODE + N (see smarttubetv/build.gradle).
 */
final class PhoneUpdater {
    static final String REPO = "shreyas-bangera/smarttube-phone";
    private static final String TAG = "PhoneUpdater";
    /**
     * StreamTube Beta (its own app, org.smarttube.phone.beta): follows the one "beta" pre-release, which every push to
     * the development branch replaces, instead of the latest stable build-N
     */
    static final boolean BETA = com.liskovsoft.smartyoutubetv2.tv.BuildConfig.PHONE_BETA;
    private static final String LATEST_URL = "https://api.github.com/repos/" + REPO
            + (BETA ? "/releases/tags/beta" : "/releases/latest");
    // Each release carries one APK per processor (arm64, arm32, universal), each as the versioned file
    // (StreamTube-Phone-<version>-arm64.apk) and under a fixed name (StreamTube-arm64.apk, the direct download link).
    // The arm64 APK also goes out as SmartTube-Phone-arm64.apk for old builds.
    private static final String APK_ARCH = deviceArch();
    private static final String APK_NAME = (BETA ? "StreamTube-Beta-" : "StreamTube-") + APK_ARCH + ".apk";
    private static final int BASE_VERSION_CODE = 2462;
    private static final String ACTION_INSTALL_STATUS = "org.smarttube.phone.INSTALL_STATUS";

    private static final String PREFS = "phone_updates";
    private static final String KEY_NOTIFY = "notify";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_LATEST_BUILD = "latest_build";
    private static final String KEY_LAST_BANNER = "last_banner";
    /** Background checks: at most this often (coming back to Home) */
    private static final long BACKGROUND_CHECK_INTERVAL_MS = 30 * 60 * 1000L;
    /** The Home card for a waiting update: once per app session, and not more often than this */
    private static final long BANNER_INTERVAL_MS = 3 * 60 * 60 * 1000L;

    private static long sLastBackgroundCheck;
    private static boolean sBannerShownThisSession;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static final class Release {
        int build;
        String apkUrl;
        long size;
        long publishedAt;
        final List<String> notes = new ArrayList<>();
    }

    interface CheckCallback {
        /** On the main thread. release == null means the check failed. */
        void onResult(Release release);
    }

    interface DownloadListener {
        void onProgress(long done, long total);

        /** The APK is with Android's installer: its confirmation screen comes next */
        void onHandedToInstaller();

        void onFailed(String error);
    }

    interface InstallListener {
        /** Android's "Update" confirmation, to be started by the screen in front */
        void onConfirmationNeeded(Intent confirm);

        void onInstallFailed(String message);
    }

    static final class Download {
        private volatile boolean mCancelled;

        void cancel() {
            mCancelled = true;
        }
    }

    private static WeakReference<InstallListener> sInstallListener;

    private PhoneUpdater() {
    }

    // region Versions and settings

    /** Build number of the installed app (0 for local builds) */
    static int currentBuild(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return (int) Math.max(0, code - BASE_VERSION_CODE);
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    static String currentVersionName(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    static boolean isNewer(Context context, Release release) {
        int current = currentBuild(context);
        return release != null && (current == 0 || release.build > current);
    }

    static String settingsTitle(Context context) {
        int latest = prefs(context).getInt(KEY_LATEST_BUILD, 0);
        int current = currentBuild(context);
        if (current > 0 && latest > current) {
            return context.getString(R.string.phone_update_check_available, latest);
        }
        return context.getString(R.string.phone_update_check);
    }

    static boolean isNotifyEnabled(Context context) {
        return prefs(context).getBoolean(KEY_NOTIFY, true);
    }

    static void setNotifyEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_NOTIFY, enabled).apply();
    }

    static long lastCheckTime(Context context) {
        return prefs(context).getLong(KEY_LAST_CHECK, 0);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void open(Context context) {
        Intent intent = new Intent(context, PhoneUpdateActivity.class);
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    // endregion

    // region Checking

    static void checkAsync(Context context, CheckCallback callback) {
        checkAsync(context, false, callback);
    }

    /**
     * @param quick only the build number (background checks): the releases page answers at once and has no rate limit
     */
    static void checkAsync(Context context, boolean quick, CheckCallback callback) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            Release release = null;
            if (quick) {
                try {
                    release = fetchLatestFromRedirect();
                } catch (Throwable e) {
                    Log.w(TAG, "Releases page check failed: " + e);
                }
            }
            if (release == null) {
                try {
                    release = fetchLatest();
                } catch (Throwable e) {
                    Log.w(TAG, "GitHub API check failed, trying the releases page: " + e);
                }
            }
            if (release == null && !quick) {
                try {
                    release = fetchLatestFromRedirect();
                } catch (Throwable e) {
                    Log.w(TAG, "Releases page check failed: " + e);
                }
            }
            if (release != null) {
                prefs(app).edit()
                        .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                        .putInt(KEY_LATEST_BUILD, release.build)
                        .apply();
            }
            final Release result = release;
            MAIN.post(() -> callback.onResult(result));
        }, "PhoneUpdaterCheck").start();
    }

    /**
     * Coming back to Home: look for a new build (at most every half hour) and, while one is waiting, show the
     * Update card once per app session (at most every few hours).
     */
    static void checkInBackground(Activity activity) {
        if (!isNotifyEnabled(activity) || sBannerShownThisSession) {
            return;
        }
        SharedPreferences prefs = prefs(activity);
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_BANNER, 0) < BANNER_INTERVAL_MS) {
            return;
        }
        int known = prefs.getInt(KEY_LATEST_BUILD, 0);
        int current = currentBuild(activity);
        if (current > 0 && known > current) {
            showBanner(activity, known); // already known from an earlier check
            return;
        }
        long now = System.currentTimeMillis();
        if (now - sLastBackgroundCheck < BACKGROUND_CHECK_INTERVAL_MS) {
            return;
        }
        sLastBackgroundCheck = now;
        checkAsync(activity, true, release -> {
            Log.i(TAG, "Background check: latest " + (release != null ? release.build : -1) + ", installed " + currentBuild(activity));
            if (release != null && isNewer(activity, release)) {
                showBanner(activity, release.build);
            }
        });
    }

    private static void showBanner(Activity activity, int build) {
        if (sBannerShownThisSession || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        Log.i(TAG, "Update card: build " + build + " is waiting");
        sBannerShownThisSession = true;
        prefs(activity).edit().putLong(KEY_LAST_BANNER, System.currentTimeMillis()).apply();
        PhoneUpdateBanner.show(activity, build);
    }

    // endregion

    // region Downloading and installing

    static Download download(Context context, Release release, DownloadListener listener) {
        Download handle = new Download();
        Context app = context.getApplicationContext();
        new Thread(() -> {
            PackageInstaller installer = app.getPackageManager().getPackageInstaller();
            int sessionId = -1;
            String error = null;
            try {
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(app.getPackageName());
                if (release.size > 0) {
                    params.setSize(release.size);
                }
                sessionId = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                    HttpURLConnection connection = open(release.apkUrl);
                    long length = connection.getContentLengthLong();
                    final long total = length > 0 ? length : release.size;
                    try (InputStream in = connection.getInputStream();
                         OutputStream out = session.openWrite(APK_NAME, 0, total > 0 ? total : -1)) {
                        byte[] buffer = new byte[64 * 1024];
                        long done = 0;
                        long lastReport = 0;
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            if (handle.mCancelled) {
                                throw new InterruptedException("cancelled");
                            }
                            out.write(buffer, 0, read);
                            done += read;
                            long now = System.currentTimeMillis();
                            if (now - lastReport > 120) {
                                lastReport = now;
                                final long doneNow = done;
                                MAIN.post(() -> listener.onProgress(doneNow, total));
                            }
                        }
                        final long doneAll = done;
                        MAIN.post(() -> listener.onProgress(doneAll, total > 0 ? total : doneAll));
                        session.fsync(out);
                    } finally {
                        connection.disconnect();
                    }

                    // Explicit receiver: the manifest receiver has no intent filter, so an action-only broadcast never reached it
                    Intent status = new Intent(app, InstallReceiver.class).setAction(ACTION_INSTALL_STATUS);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent pending = PendingIntent.getBroadcast(app, sessionId, status, flags);
                    session.commit(pending.getIntentSender());
                }
            } catch (InterruptedException e) {
                error = "";
            } catch (Throwable e) {
                Log.w(TAG, "Update download failed: " + e);
                error = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            }
            if (error != null && sessionId != -1) {
                try {
                    installer.abandonSession(sessionId);
                } catch (Throwable ignored) {
                    // already gone
                }
            }
            final String failure = error;
            MAIN.post(() -> {
                if (failure == null) {
                    listener.onHandedToInstaller();
                } else {
                    listener.onFailed(failure);
                }
            });
        }, "PhoneUpdaterDownload").start();
        return handle;
    }

    static void setInstallListener(InstallListener listener) {
        sInstallListener = listener != null ? new WeakReference<>(listener) : null;
    }

    private static void reportInstallFailure(Context context, String message) {
        InstallListener listener = sInstallListener != null ? sInstallListener.get() : null;
        if (listener != null) {
            listener.onInstallFailed(message);
        } else {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        }
    }

    /** Receives the installer's answer: shows Android's confirmation screen, or explains a failure */
    public static class InstallReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            Log.i(TAG, "Install status " + status + ": " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm == null) {
                    return;
                }
                // The update screen is in front: let it open the confirmation (always allowed for a visible screen)
                InstallListener listener = sInstallListener != null ? sInstallListener.get() : null;
                if (listener != null) {
                    listener.onConfirmationNeeded(confirm);
                    return;
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirm);
                } catch (Throwable e) {
                    reportInstallFailure(context, context.getString(R.string.phone_update_failed, String.valueOf(e.getMessage())));
                }
            } else if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
                reportInstallFailure(context, context.getString(R.string.phone_update_conflict));
            } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                reportInstallFailure(context, context.getString(R.string.phone_update_cancelled));
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                reportInstallFailure(context, context.getString(R.string.phone_update_failed, message != null ? message : String.valueOf(status)));
            }
        }
    }

    // endregion

    // region GitHub

    /**
     * No API (and no API rate limit): github.com/.../releases/latest redirects to .../releases/tag/build-N
     */
    private static Release fetchLatestFromRedirect() throws Exception {
        if (BETA) {
            return null; // the beta release is never "latest": the API only
        }
        HttpURLConnection connection = (HttpURLConnection) new URL("https://github.com/" + REPO + "/releases/latest").openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "StreamTube-Updater");
        try {
            connection.getResponseCode();
            String location = connection.getHeaderField("Location");
            Matcher matcher = location != null ? Pattern.compile("/tag/build-(\\d+)").matcher(location) : null;
            if (matcher == null || !matcher.find()) {
                return null;
            }
            Release release = new Release();
            release.build = Integer.parseInt(matcher.group(1));
            release.apkUrl = "https://github.com/" + REPO + "/releases/download/build-" + release.build + "/" + APK_NAME;
            return release;
        } finally {
            connection.disconnect();
        }
    }

    private static Release fetchLatest() throws Exception {
        HttpURLConnection connection = open(LATEST_URL);
        String body;
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, read);
            }
            body = bytes.toString("UTF-8");
        } finally {
            connection.disconnect();
        }

        JSONObject json = new JSONObject(body);
        String tag = json.optString("tag_name", "");
        Release release = new Release();
        if (BETA) {
            // "StreamTube Beta 32.72-beta-12": the beta build's number (its version code is BASE_VERSION_CODE + it)
            Matcher matcher = Pattern.compile("beta-(\\d+)").matcher(json.optString("name", ""));
            if (!"beta".equals(tag) || !matcher.find()) {
                return null;
            }
            release.build = Integer.parseInt(matcher.group(1));
        } else {
            if (!tag.startsWith("build-")) {
                return null;
            }
            release.build = Integer.parseInt(tag.substring("build-".length()));
        }
        JSONArray assets = json.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.optString("name");
                // The versioned file (StreamTube-Phone-32.72.81-arm64.apk); the fixed name is the same APK
                boolean versioned = name.startsWith("StreamTube-Phone-") && name.endsWith("-" + APK_ARCH + ".apk");
                if (versioned || (APK_NAME.equals(name) && TextUtils.isEmpty(release.apkUrl))) {
                    release.apkUrl = asset.optString("browser_download_url");
                    release.size = asset.optLong("size", 0);
                }
            }
        }
        if (TextUtils.isEmpty(release.apkUrl)) {
            release.apkUrl = "https://github.com/" + REPO + "/releases/download/" + tag + "/" + APK_NAME;
        }
        release.publishedAt = parseDate(json.optString("published_at", ""));
        parseNotes(json.optString("body", ""), release.notes);
        return release;
    }

    /**
     * The APK for this phone: arm64 on 64-bit ARM, arm32 on 32-bit ARM, otherwise (x86 emulators, Chromebooks) the
     * universal one. Same package and key, so a phone can move from one to another as an ordinary update.
     */
    private static String deviceArch() {
        for (String abi : Build.SUPPORTED_64_BIT_ABIS) {
            if ("arm64-v8a".equals(abi)) {
                return "arm64";
            }
        }
        for (String abi : Build.SUPPORTED_32_BIT_ABIS) {
            if ("armeabi-v7a".equals(abi)) {
                return Build.SUPPORTED_64_BIT_ABIS.length == 0 ? "arm32" : "universal";
            }
        }
        return "universal";
    }

    /** "- change" lines from the release text (the build workflow lists the commits since the previous build) */
    private static void parseNotes(String body, List<String> notes) {
        if (TextUtils.isEmpty(body)) {
            return;
        }
        for (String line : body.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                String note = trimmed.substring(2).trim();
                if (!note.isEmpty() && notes.size() < 30) {
                    notes.add(note);
                }
            }
        }
    }

    private static long parseDate(String iso) {
        if (TextUtils.isEmpty(iso)) {
            return 0;
        }
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            java.util.Date date = format.parse(iso);
            return date != null ? date.getTime() : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "StreamTube-Updater");
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        int code = connection.getResponseCode();
        // GitHub serves release files from another host: follow that redirect by hand if needed
        for (int hops = 0; hops < 5 && code >= 300 && code < 400; hops++) {
            String next = connection.getHeaderField("Location");
            connection.disconnect();
            connection = (HttpURLConnection) new URL(new URL(url), next).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "StreamTube-Updater");
            code = connection.getResponseCode();
        }
        if (code != 200) {
            connection.disconnect();
            throw new java.io.IOException("HTTP " + code);
        }
        return connection;
    }

    // endregion
}
