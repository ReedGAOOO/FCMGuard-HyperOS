package com.reed.fcmguard;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Best-effort detector for installed apps that declare standard FCM/GCM receive
 * components. It does not inspect traffic or accounts and runs only when requested.
 */
public final class FcmAppScanner {
    private static final String ACTION_FCM = "com.google.firebase.MESSAGING_EVENT";
    private static final String ACTION_GCM_RECEIVE = "com.google.android.c2dm.intent.RECEIVE";

    private FcmAppScanner() {}

    public static final class AppEntry {
        public final String packageName;
        public final String label;

        AppEntry(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    public static List<AppEntry> scan(Context context) {
        return scanDetailed(context).apps;
    }

    /** Scan result carries query failures so maintenance never treats an incomplete scan as empty. */
    public static ScanResult scanDetailed(Context context) {
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PackageManager.MATCH_ALL
                : 0;

        Map<String, Boolean> packages = new LinkedHashMap<>();
        ScanResult result = new ScanResult();

        try {
            List<ResolveInfo> services = pm.queryIntentServices(new Intent(ACTION_FCM), flags);
            if (services != null) {
                for (ResolveInfo resolveInfo : services) {
                    ServiceInfo info = resolveInfo.serviceInfo;
                    if (info != null && info.packageName != null && info.enabled
                            && info.applicationInfo != null && info.applicationInfo.enabled) {
                        packages.put(info.packageName, Boolean.TRUE);
                    }
                }
            } else {
                result.complete = false;
                result.error = "FCM service query returned null";
            }
        } catch (Throwable error) {
            result.complete = false;
            result.error = describe(error);
        }

        // Legacy GCM / compatibility path still used by some apps and libraries.
        try {
            List<ResolveInfo> receivers = pm.queryBroadcastReceivers(
                    new Intent(ACTION_GCM_RECEIVE), flags);
            if (receivers != null) {
                for (ResolveInfo resolveInfo : receivers) {
                    ActivityInfo info = resolveInfo.activityInfo;
                    if (info != null && info.packageName != null && info.enabled
                            && info.applicationInfo != null && info.applicationInfo.enabled) {
                        packages.put(info.packageName, Boolean.TRUE);
                    }
                }
            } else {
                result.complete = false;
                if (result.error.isEmpty()) result.error = "GCM receiver query returned null";
                else result.error += "; GCM receiver query returned null";
            }
        } catch (Throwable error) {
            result.complete = false;
            if (result.error.isEmpty()) result.error = describe(error);
            else result.error += "; " + describe(error);
        }

        List<AppEntry> apps = new ArrayList<>();
        for (String packageName : packages.keySet()) {
            if (packageName == null || packageName.equals(context.getPackageName()) ||
                    "com.google.android.gms".equals(packageName) ||
                    "com.android.vending".equals(packageName)) {
                continue;
            }

            try {
                ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
                if (!appInfo.enabled || !AutostartStatusReader.isUserInstalledApp(appInfo.flags)) continue;
                // Keep the list focused on user-facing apps that can actually be opened.
                if (pm.getLaunchIntentForPackage(packageName) == null) continue;
                CharSequence labelCs = pm.getApplicationLabel(appInfo);
                String label = labelCs == null ? packageName : labelCs.toString().trim();
                if (label.isEmpty()) label = packageName;
                apps.add(new AppEntry(packageName, label));
            } catch (Throwable error) {
                result.complete = false;
                if (result.error.isEmpty()) result.error = describe(error);
                else result.error += "; " + describe(error);
            }
        }

        Collections.sort(apps, (a, b) -> {
            int byLabel = a.label.toLowerCase(Locale.ROOT)
                    .compareTo(b.label.toLowerCase(Locale.ROOT));
            if (byLabel != 0) return byLabel;
            return a.packageName.compareTo(b.packageName);
        });
        result.apps.addAll(apps);
        return result;
    }

    public static final class ScanResult {
        public final List<AppEntry> apps = new ArrayList<>();
        public boolean complete = true;
        public String error = "";
    }

    private static String describe(Throwable error) {
        return error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
    }
}
