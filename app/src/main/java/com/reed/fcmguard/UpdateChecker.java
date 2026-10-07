package com.reed.fcmguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * In-app update check against this fork's GitHub Releases. No external
 * library: one unauthenticated /releases/latest call, a semver-style tag
 * comparison, and an APK download into the app cache served to the system
 * installer through UpdateApkProvider.
 */
public final class UpdateChecker {
    public interface Listener {
        void onNewer(ReleaseInfo info);
        void onUpToDate();
        void onError();
    }

    public static class ReleaseInfo {
        public String tag;
        public String url;
    }

    private static final String REPO = "longhangoc/FCMGuard-HyperOS";
    private static final String LATEST_APK = "FCMGuard-HyperOS.apk";
    private static final String AUTHORITY = "com.reed.fcmguard.updateapk";
    private static final long AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    private static final String KEY_LAST_CHECK = "update_last_check_at";
    private static final String KEY_DISMISSED_TAG = "update_dismissed_tag";
    private static final String KEY_PENDING_INSTALL = "update_pending_install";

    private UpdateChecker() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(SettingsGuard.PREFS, Context.MODE_PRIVATE);
    }

    public static String currentVersion(Context context) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pi.versionName == null ? "0.0.0" : pi.versionName;
        } catch (Throwable t) {
            return "0.0.0";
        }
    }

    /** Compares "v1.6.1"-style tags numerically against "1.6.1"-style names. */
    static boolean isNewer(String remoteTag, String currentName) {
        int[] remote = parse(remoteTag);
        int[] current = parse(currentName);
        for (int i = 0; i < 3; i++) {
            if (remote[i] != current[i]) return remote[i] > current[i];
        }
        return false;
    }

    private static int[] parse(String version) {
        int[] out = new int[3];
        if (version == null) return out;
        String cleaned = version.trim().replaceFirst("(?i)^v", "");
        String[] parts = cleaned.split("\\.");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** Silent check at most once a day; prompts only for un-dismissed tags. */
    public static void autoCheck(final Activity activity) {
        SharedPreferences p = prefs(activity);
        long now = System.currentTimeMillis();
        if (now - p.getLong(KEY_LAST_CHECK, 0L) < AUTO_CHECK_INTERVAL_MS) return;
        p.edit().putLong(KEY_LAST_CHECK, now).apply();

        checkInternal(activity, new Listener() {
            @Override public void onNewer(ReleaseInfo info) {
                String dismissed = prefs(activity).getString(KEY_DISMISSED_TAG, "");
                if (!info.tag.equals(dismissed)) {
                    showUpdateDialog(activity, info, true);
                }
            }
            @Override public void onUpToDate() {}
            @Override public void onError() {}
        });
    }

    public static void manualCheck(final Activity activity, final ProgressDialog progress) {
        checkInternal(activity, new Listener() {
            @Override public void onNewer(ReleaseInfo info) {
                dismissQuietly(progress);
                showUpdateDialog(activity, info, false);
            }
            @Override public void onUpToDate() {
                dismissQuietly(progress);
                Toast.makeText(activity, R.string.update_latest, Toast.LENGTH_SHORT).show();
            }
            @Override public void onError() {
                dismissQuietly(progress);
                Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static void checkInternal(final Activity activity, final Listener listener) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                final ReleaseInfo info = fetchLatest();
                if (info == null) {
                    main.post(new Runnable() { @Override public void run() { listener.onError(); } });
                    return;
                }
                if (isNewer(info.tag, currentVersion(activity))) {
                    main.post(new Runnable() { @Override public void run() { listener.onNewer(info); } });
                } else {
                    main.post(new Runnable() { @Override public void run() { listener.onUpToDate(); } });
                }
            }
        }).start();
    }

    private static ReleaseInfo fetchLatest() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONObject root = new JSONObject(sb.toString());
                JSONArray assets = root.optJSONArray("assets");
                if (assets == null) return null;

                ReleaseInfo info = new ReleaseInfo();
                info.tag = root.optString("tag_name", "");
                String fallback = null;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) continue;
                    String name = asset.optString("name", "");
                    String url = asset.optString("browser_download_url", "");
                    if (url.isEmpty() || !name.endsWith(".apk")) continue;
                    if (LATEST_APK.equals(name)) {
                        info.url = url;
                        break;
                    }
                    if (fallback == null) fallback = url;
                }
                if (info.url == null) info.url = fallback;
                if (info.tag.isEmpty() || info.url == null) return null;
                return info;
            } finally {
                conn.disconnect();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static void showUpdateDialog(final Activity activity, final ReleaseInfo info,
                                         final boolean auto) {
        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.update_available_title, info.tag))
                .setMessage(R.string.update_available_body)
                .setPositiveButton(R.string.update_download, (dialog, which) -> {
                    if (auto) {
                        prefs(activity).edit().putString(KEY_DISMISSED_TAG, info.tag).apply();
                    }
                    downloadAndInstall(activity, info);
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    if (auto) {
                        prefs(activity).edit().putString(KEY_DISMISSED_TAG, info.tag).apply();
                    }
                })
                .show();
    }

    public static void downloadAndInstall(final Activity activity, final ReleaseInfo info) {
        final ProgressDialog progress = new ProgressDialog(activity);
        progress.setMessage(activity.getString(R.string.update_downloading));
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setCancelable(false);
        progress.show();

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(info.url).openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(30000);
                    conn.connect();
                    long total = conn.getContentLength();
                    BufferedInputStream in = new BufferedInputStream(conn.getInputStream());
                    File tmp = new File(activity.getCacheDir(), UpdateApkProvider.FILE_NAME + ".tmp");
                    FileOutputStream out = new FileOutputStream(tmp);
                    byte[] buffer = new byte[8192];
                    long done = 0;
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                        done += n;
                        if (total > 0) {
                            final int percent = (int) (done * 100 / total);
                            activity.runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    try { progress.setProgress(percent); } catch (Throwable ignored) {}
                                }
                            });
                        }
                    }
                    out.flush();
                    out.getFD().sync();
                    out.close();
                    in.close();
                    conn.disconnect();

                    File target = UpdateApkProvider.apkFile(activity);
                    if (target.exists()) target.delete();
                    if (!tmp.renameTo(target)) throw new IllegalStateException("rename failed");

                    activity.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            dismissQuietly(progress);
                            installFromCache(activity);
                        }
                    });
                } catch (Throwable t) {
                    activity.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            dismissQuietly(progress);
                            Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();
    }

    public static void installFromCache(Activity activity) {
        if (Build.VERSION.SDK_INT >= 26
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            prefs(activity).edit().putBoolean(KEY_PENDING_INSTALL, true).apply();
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
            } catch (Throwable ignored) {}
            return;
        }
        fireInstall(activity);
    }

    /** Called from onResume after the unknown-sources settings round-trip. */
    public static void resumePendingInstall(Activity activity) {
        if (!prefs(activity).getBoolean(KEY_PENDING_INSTALL, false)) return;
        prefs(activity).edit().putBoolean(KEY_PENDING_INSTALL, false).apply();
        if (Build.VERSION.SDK_INT >= 26
                && !activity.getPackageManager().canRequestPackageInstalls()) return;
        if (UpdateApkProvider.apkFile(activity).exists()) {
            fireInstall(activity);
        }
    }

    private static void fireInstall(Activity activity) {
        Toast.makeText(activity, R.string.update_installing, Toast.LENGTH_SHORT).show();
        Uri uri = Uri.parse("content://" + AUTHORITY + "/" + UpdateApkProvider.FILE_NAME);
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(intent);
            return;
        } catch (Throwable ignored) {}

        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, "application/vnd.android.package-archive");
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(view);
        } catch (Throwable t) {
            Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private static void dismissQuietly(ProgressDialog progress) {
        try {
            if (progress != null && progress.isShowing()) progress.dismiss();
        } catch (Throwable ignored) {}
    }
}
