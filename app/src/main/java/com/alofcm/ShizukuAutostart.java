package com.alofcm;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

/**
 * Reads iManager's whole Autostart table with shell (ADB) rights through Shizuku.
 *
 * On OriginOS 7 a normal app only gets its own row of bg_start_up_apps, but the shell
 * user gets every row (verified with "adb shell content query"). Shizuku lets AloFCM run
 * that same read-only query on the phone. Nothing is ever written.
 *
 * Optional: without Shizuku the app falls back to the user's own marks.
 */
final class ShizukuAutostart {
    static final String MANAGER_PACKAGE = "moe.shizuku.privileged.api";
    static final int REQUEST_CODE = 4270;

    enum State {
        /** Shizuku app not installed. */
        NOT_INSTALLED,
        /** Installed, but its service is not running (needs starting after each reboot). */
        NOT_RUNNING,
        /** Running, AloFCM not authorised yet. */
        NO_PERMISSION,
        READY
    }

    private static final String[] QUERY = {
            "content", "query", "--uri",
            "content://" + AutostartStatusReader.KNOWN_AUTHORITY + "/" + AutostartStatusReader.TABLE
    };
    private static final Pattern PKG = Pattern.compile("pkgname=([^,\\s]+)");
    private static final Pattern STATE = Pattern.compile("currentstate=(\\d+)");

    /** Last successful read: package -> Autostart allowed. Null until one succeeds. */
    private static volatile Map<String, Boolean> states;

    private ShizukuAutostart() {}

    static State state(Context context) {
        try {
            if (!Shizuku.pingBinder()) {
                return isInstalled(context) ? State.NOT_RUNNING : State.NOT_INSTALLED;
            }
            if (Shizuku.isPreV11()) return State.NOT_RUNNING;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    ? State.READY : State.NO_PERMISSION;
        } catch (Throwable t) {
            return State.NOT_RUNNING;
        }
    }

    private static boolean isInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(MANAGER_PACKAGE, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** TRUE / FALSE from the last shell read, or null when unknown. */
    static Boolean lookup(String packageName) {
        Map<String, Boolean> map = states;
        return map == null ? null : map.get(packageName);
    }

    static boolean hasData() {
        return states != null;
    }

    /**
     * Re-reads the table. Blocking: call off the UI thread. Returns true when the read
     * worked; on failure the previous data is dropped so stale states are never shown.
     */
    static boolean refresh(Context context) {
        if (state(context) != State.READY) {
            states = null;
            return false;
        }
        Process process = null;
        try {
            // Shizuku.newProcess is not public API in v13; it is the documented-by-usage
            // way to run a shell command without shipping an AIDL user service.
            Method newProcess = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            newProcess.setAccessible(true);
            process = (Process) newProcess.invoke(null, QUERY, null, null);

            Map<String, Boolean> map = new HashMap<>();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher pkg = PKG.matcher(line);
                Matcher st = STATE.matcher(line);
                if (pkg.find() && st.find()) {
                    // vivo: 0 = Autostart allowed, 1 = forbidden.
                    map.put(pkg.group(1), "0".equals(st.group(1)));
                }
            }
            process.waitFor();
            if (map.isEmpty()) {
                states = null;
                return false;
            }
            states = map;
            return true;
        } catch (Throwable t) {
            states = null;
            return false;
        } finally {
            if (process != null) {
                try { process.destroy(); } catch (Throwable ignored) {}
            }
        }
    }
}
