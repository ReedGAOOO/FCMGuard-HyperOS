package com.alofcm;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

/**
 * Best-effort, read-only probe for vivo OriginOS / Funtouch OS Autostart state.
 *
 * vivo keeps Autostart in iManager's permission provider, table bg_start_up_apps, one row
 * per app (pkgname) with currentstate 0 = allowed, 1 = forbidden. On some builds the
 * provider is not readable by other apps; callers then get UNKNOWN and
 * {@link #lastFailure()} says why, so the UI never shows a guessed state.
 *
 * Thread-safe: callers run it on a background executor so a long FCM app list never
 * blocks the UI thread.
 */
public final class AutostartStatusReader {
    private static final String AUTHORITY = "com.vivo.permissionmanager.provider.permission";
    private static final Uri BG_START_UP_APPS = Uri.parse("content://" + AUTHORITY + "/bg_start_up_apps");
    private static final String[] PACKAGE_COLUMNS = {"pkgname", "packagename", "package_name"};
    private static final String[] STATE_COLUMNS = {"currentstate", "currentState", "state"};

    public enum Status {
        ENABLED,
        PARTIAL,
        DISABLED,
        UNKNOWN
    }

    /** Why the last UNKNOWN happened. */
    public enum Failure {
        NONE,
        /** The provider does not exist on this ROM. */
        NO_PROVIDER,
        /** The provider exists but OriginOS refused the read. */
        DENIED,
        /** The provider answered but has no row for the app. */
        NO_ROW
    }

    private static volatile Failure lastFailure = Failure.NONE;

    private AutostartStatusReader() {}

    public static Failure lastFailure() {
        return lastFailure;
    }

    public static Status check(Context context, String packageName) {
        return check(context, packageName, -1);
    }

    /** The uid is unused on vivo; kept so callers stay identical to the HyperOS build. */
    public static Status check(Context context, String packageName, int uid) {
        Failure failure = Failure.NO_ROW;
        for (String pkgColumn : PACKAGE_COLUMNS) {
            Cursor cursor = null;
            try {
                cursor = context.getContentResolver().query(
                        BG_START_UP_APPS, null, pkgColumn + "=?", new String[] {packageName}, null);
                if (cursor == null) {
                    lastFailure = Failure.NO_PROVIDER;
                    return Status.UNKNOWN;
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
                lastFailure = Failure.DENIED;
                return Status.UNKNOWN;
            } catch (IllegalArgumentException e) {
                // Unknown URI on this ROM, or an unknown column: try the next column name.
                failure = Failure.NO_PROVIDER;
            } catch (Throwable ignored) {
                // e.g. SQLiteException for a column this build does not have.
                failure = Failure.NO_PROVIDER;
            } finally {
                if (cursor != null) {
                    try { cursor.close(); } catch (Throwable ignored) {}
                }
            }
        }
        lastFailure = failure;
        return Status.UNKNOWN;
    }
}
