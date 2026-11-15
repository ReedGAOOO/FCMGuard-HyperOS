package com.alofcm;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Best-effort, read-only probe for vivo OriginOS / Funtouch OS Autostart state.
 *
 * vivo keeps Autostart in iManager's permission provider, table bg_start_up_apps, one row
 * per app (pkgname) with currentstate 0 = allowed, 1 = forbidden. The authority has moved
 * between ROM releases, so besides the known one, every content provider shipped by a
 * vivo / iQOO / BBK system package whose authority mentions "permission" is tried once;
 * the first that answers is remembered for the rest of the process.
 *
 * When nothing can be read, callers get UNKNOWN and {@link #lastFailure()} says why, so the
 * UI never shows a guessed state. {@link #diagnose(Context)} writes a report the user can
 * send to find the right provider on their build.
 *
 * Thread-safe: callers run it on a background executor so a long FCM app list never
 * blocks the UI thread.
 */
public final class AutostartStatusReader {
    static final String KNOWN_AUTHORITY = "com.vivo.permissionmanager.provider.permission";
    static final String TABLE = "bg_start_up_apps";
    private static final String[] PACKAGE_COLUMNS = {"pkgname", "packagename", "package_name"};
    private static final String[] STATE_COLUMNS = {"currentstate", "currentState", "state"};
    /** System packages whose providers are worth probing. */
    private static final String[] VENDOR_PREFIXES = {"com.vivo.", "com.iqoo.", "com.bbk."};

    public enum Status {
        ENABLED,
        PARTIAL,
        DISABLED,
        UNKNOWN
    }

    /** Why the last UNKNOWN happened. */
    public enum Failure {
        NONE,
        /** No readable Autostart provider on this ROM. */
        NO_PROVIDER,
        /** A provider exists but OriginOS refused the read. */
        DENIED,
        /** The provider answered but has no row for the app. */
        NO_ROW
    }

    private static final Object LOCK = new Object();
    private static volatile Failure lastFailure = Failure.NONE;
    /** Authority that answered, once found; null until then. */
    private static String workingAuthority;
    private static List<String> candidates;

    private AutostartStatusReader() {}

    public static Failure lastFailure() {
        return lastFailure;
    }

    public static Status check(Context context, String packageName) {
        return check(context, packageName, -1);
    }

    /** The uid is unused on vivo; kept so callers stay identical to the HyperOS build. */
    public static Status check(Context context, String packageName, int uid) {
        String known;
        synchronized (LOCK) {
            known = workingAuthority;
        }
        if (known != null) return query(context, known, packageName, null);

        Failure worst = Failure.NO_PROVIDER;
        for (String authority : candidates(context)) {
            Failure[] failure = new Failure[1];
            Status status = query(context, authority, packageName, failure);
            if (status != Status.UNKNOWN || failure[0] == Failure.NO_ROW) {
                // The table exists and answered: keep using this authority.
                synchronized (LOCK) {
                    workingAuthority = authority;
                }
                return status;
            }
            if (failure[0] == Failure.DENIED) worst = Failure.DENIED;
        }
        lastFailure = worst;
        return Status.UNKNOWN;
    }

    /** Known authority first, then vendor "permission" providers found on the device. */
    private static List<String> candidates(Context context) {
        synchronized (LOCK) {
            if (candidates != null) return candidates;
            LinkedHashSet<String> out = new LinkedHashSet<>();
            out.add(KNOWN_AUTHORITY);
            for (ProviderInfo p : vendorProviders(context)) {
                if (p.authority == null) continue;
                for (String a : p.authority.split(";")) {
                    if (a.toLowerCase(Locale.ROOT).contains("permission")) out.add(a);
                }
            }
            candidates = new ArrayList<>(out);
            return candidates;
        }
    }

    /** Content providers declared by installed vivo / iQOO / BBK system packages. */
    static List<ProviderInfo> vendorProviders(Context context) {
        List<ProviderInfo> out = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        List<PackageInfo> packages;
        try {
            packages = pm.getInstalledPackages(PackageManager.GET_PROVIDERS);
        } catch (Throwable t) {
            return out;
        }
        for (PackageInfo info : packages) {
            if (info.packageName == null || !isVendor(info.packageName)) continue;
            ApplicationInfo app = info.applicationInfo;
            if (app != null && (app.flags & ApplicationInfo.FLAG_SYSTEM) == 0) continue;
            if (info.providers == null) continue;
            for (ProviderInfo p : info.providers) out.add(p);
        }
        return out;
    }

    private static boolean isVendor(String packageName) {
        for (String prefix : VENDOR_PREFIXES) {
            if (packageName.startsWith(prefix)) return true;
        }
        return false;
    }

    private static Status query(Context context, String authority, String packageName, Failure[] out) {
        Uri uri = Uri.parse("content://" + authority + "/" + TABLE);
        Failure failure = Failure.NO_PROVIDER;
        for (String pkgColumn : PACKAGE_COLUMNS) {
            Cursor cursor = null;
            try {
                cursor = context.getContentResolver().query(
                        uri, null, pkgColumn + "=?", new String[] {packageName}, null);
                if (cursor == null) {
                    failure = Failure.NO_PROVIDER;
                    break;
                }
                if (!cursor.moveToFirst()) {
                    failure = Failure.NO_ROW;
                    continue;
                }
                for (String stateColumn : STATE_COLUMNS) {
                    int index = cursor.getColumnIndex(stateColumn);
                    if (index < 0) continue;
                    int state = cursor.getInt(index);
                    if (state == 0) return Status.ENABLED;
                    if (state == 1) return Status.DISABLED;
                }
                failure = Failure.NO_ROW;
            } catch (SecurityException e) {
                failure = Failure.DENIED;
                break;
            } catch (Throwable ignored) {
                // Unknown table or column on this build: try the next column name.
            } finally {
                if (cursor != null) {
                    try { cursor.close(); } catch (Throwable ignored) {}
                }
            }
        }
        if (out != null) out[0] = failure;
        else lastFailure = failure;
        return Status.UNKNOWN;
    }

    /** Packages whose providers may hold Autostart / background-start state. */
    private static final String[] RELATED_PACKAGES = {
            "com.vivo.permissionmanager", "com.iqoo.secure", "com.vivo.abe",
            "com.vivo.safecenter", "com.vivo.appfilter", "com.iqoo.powersaving"
    };

    /** Extra authorities seen on OriginOS 7 that may hold app life-control state. */
    private static final String[] EXTRA_AUTHORITIES = {
            "com.vivo.abe.thirdapp.lifecontrol.provider",
            "com.vivo.abe.provider.third",
            "com.vivo.abe.highpower.provider",
            "com.vivo.abe.configlist.provider",
            "com.vivo.safecenter.provider.secureprovider",
            "com.vivo.appfilter.provider.secureprovider"
    };

    /**
     * Short plain-text report for the user to send. The probes come first so they survive
     * if the text is cut when pasted. Only counts, column names and error types are shown,
     * never the contents of another app's rows.
     */
    static String diagnose(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("AloFCM autostart diagnostics v2\n");
        sb.append("Device: ").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append(" / Android ")
                .append(android.os.Build.VERSION.RELEASE).append(" (SDK ")
                .append(android.os.Build.VERSION.SDK_INT).append(")\n");
        String os = systemProperty("ro.vivo.os.build.display.id");
        if (!os.isEmpty()) sb.append("OS: ").append(os).append('\n');

        sb.append("\n[Probes]\n");
        LinkedHashSet<String> authorities = new LinkedHashSet<>(candidates(context));
        for (String a : EXTRA_AUTHORITIES) authorities.add(a);
        for (String authority : authorities) {
            sb.append(authority).append('\n');
            sb.append("  /").append(TABLE).append(": ")
                    .append(probe(context, "content://" + authority + "/" + TABLE)).append('\n');
            sb.append("  /: ").append(probe(context, "content://" + authority)).append('\n');
        }
        sb.append("\n[Rows for known apps in ").append(KNOWN_AUTHORITY).append("/").append(TABLE).append("]\n");
        for (String pkg : new String[] {context.getPackageName(), "com.google.android.gms",
                "com.zing.zalo", "com.facebook.orca"}) {
            sb.append("  ").append(pkg).append(": ").append(rowsFor(context, pkg)).append('\n');
        }

        PackageManager pm = context.getPackageManager();
        sb.append("\n[Providers of related packages]\n");
        for (String pkg : RELATED_PACKAGES) {
            try {
                PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_PROVIDERS);
                sb.append(pkg).append(' ').append(info.versionName).append('\n');
                if (info.providers == null || info.providers.length == 0) {
                    sb.append("  (no providers)\n");
                    continue;
                }
                for (ProviderInfo p : info.providers) {
                    sb.append("  ").append(p.authority).append(p.exported ? " [exported" : " [private");
                    if (p.readPermission != null) sb.append(", read=").append(p.readPermission);
                    sb.append("]\n");
                }
            } catch (Throwable t) {
                sb.append(pkg).append(" -\n");
            }
        }
        return sb.toString();
    }

    /** Row count and state values (only) for one package in the known table. */
    private static String rowsFor(Context context, String pkg) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    Uri.parse("content://" + KNOWN_AUTHORITY + "/" + TABLE),
                    null, "pkgname=?", new String[] {pkg}, null);
            if (cursor == null) return "null cursor";
            StringBuilder out = new StringBuilder().append(cursor.getCount()).append(" rows");
            int state = cursor.getColumnIndex("currentstate");
            if (state >= 0 && cursor.moveToFirst()) {
                out.append(", currentstate=").append(cursor.getString(state));
            }
            return out.toString();
        } catch (Throwable t) {
            return t.getClass().getSimpleName();
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String probe(Context context, String uri) {
        Cursor cursor = null;
        try {
            Uri u = Uri.parse(uri);
            if (context.getPackageManager().resolveContentProvider(u.getAuthority(), 0) == null) {
                return "not resolvable";
            }
            cursor = context.getContentResolver().query(u, null, null, null, null);
            if (cursor == null) return "null cursor";
            String[] names = cursor.getColumnNames();
            return cursor.getCount() + " rows, columns " + java.util.Arrays.toString(names);
        } catch (Throwable t) {
            String msg = t.getMessage();
            if (msg != null && msg.length() > 120) msg = msg.substring(0, 120) + "…";
            return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String systemProperty(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object v = c.getMethod("get", String.class, String.class).invoke(null, key, "");
            return v instanceof String ? ((String) v).trim() : "";
        } catch (Throwable t) {
            return "";
        }
    }
}
