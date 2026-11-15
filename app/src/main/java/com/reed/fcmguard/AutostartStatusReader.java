package com.reed.fcmguard;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

/**
 * Best-effort, read-only probe for vivo OriginOS / Funtouch OS Autostart state.
 *
 * vivo keeps Autostart in iManager's permission provider. On many builds the provider is
 * not readable by third-party apps; callers must then present UNKNOWN rather than guess.
 * This class never changes anything.
 *
 * Thread-safe: callers run it on a background executor so a long FCM app list never
 * blocks the UI thread.
 */
public final class AutostartStatusReader {
    /**
     * Autostart table of iManager's permission provider. Unverified across builds: vivo
     * does not document it, and the neighbouring start_bg_activity table holds the
     * unrelated "background pop-up" permission, so it is deliberately not read here.
     */
    private static final String[] PROVIDER_URIS = {
            "content://com.vivo.permissionmanager.provider.permission/bg_start_up_apps",
    };
    private static final String[] PACKAGE_COLUMNS = {"pkgname", "packagename", "package_name"};
    private static final String[] STATE_COLUMNS = {"currentstate", "currentState", "state"};

    public enum Status {
        ENABLED,
        PARTIAL,
        DISABLED,
        UNKNOWN
    }

    private AutostartStatusReader() {}

    public static Status check(Context context, String packageName) {
        return check(context, packageName, -1);
    }

    /** The uid is unused on vivo; kept so callers stay identical to the HyperOS build. */
    public static Status check(Context context, String packageName, int uid) {
        for (String uri : PROVIDER_URIS) {
            Status status = query(context, uri, packageName);
            if (status != Status.UNKNOWN) return status;
        }
        return Status.UNKNOWN;
    }

    private static Status query(Context context, String uri, String packageName) {
        for (String pkgColumn : PACKAGE_COLUMNS) {
            Cursor cursor = null;
            try {
                cursor = context.getContentResolver().query(
                        Uri.parse(uri), null, pkgColumn + "=?", new String[] {packageName}, null);
                if (cursor == null) return Status.UNKNOWN;
                if (!cursor.moveToFirst()) continue;
                for (String stateColumn : STATE_COLUMNS) {
                    int index = cursor.getColumnIndex(stateColumn);
                    if (index < 0) continue;
                    // vivo stores 0 = allowed, 1 = forbidden.
                    int state = cursor.getInt(index);
                    if (state == 0) return Status.ENABLED;
                    if (state == 1) return Status.DISABLED;
                    return Status.UNKNOWN;
                }
                return Status.UNKNOWN;
            } catch (Throwable ignored) {
                // SecurityException (provider not exported) or unknown column: try next.
            } finally {
                if (cursor != null) {
                    try { cursor.close(); } catch (Throwable ignored) {}
                }
            }
        }
        return Status.UNKNOWN;
    }
}
