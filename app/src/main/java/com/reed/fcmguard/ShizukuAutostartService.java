package com.reed.fcmguard;

import android.os.RemoteException;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Runs in Shizuku's user service process, whose identity is the user-selected
 * shell/root backend. The normal app process never receives that identity.
 */
public final class ShizukuAutostartService extends IShizukuAutostartService.Stub {
    private static final int OP_MIUI_AUTOSTART = 10008;
    private static final int OP_MIUI_AUTOSTART_SWITCH = 10053;

    @Override public String setAutostart(String packageName, boolean enabled) throws RemoteException {
        if (packageName == null || !packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) {
            return "INVALID_PACKAGE";
        }

        String mode = enabled ? "allow" : "ignore";
        String primary = runAppOps(packageName, OP_MIUI_AUTOSTART, mode);
        if (!"OK".equals(primary)) return primary;

        String secondary = runAppOps(packageName, OP_MIUI_AUTOSTART_SWITCH, mode);
        if (!"OK".equals(secondary)) return secondary;
        return "OK";
    }

    private String runAppOps(String packageName, int op, String mode) {
        try {
            // ProcessBuilder arguments, rather than a shell string, prevent package names
            // from changing the command structure.
            Process process = new ProcessBuilder(
                    "cmd", "appops", "set", packageName, String.valueOf(op), mode)
                    .redirectErrorStream(true)
                    .start();
            String output = readLimited(process.getInputStream());
            int exit = process.waitFor();
            if (exit == 0) return "OK";
            return "EXIT_" + exit + compact(output);
        } catch (Throwable t) {
            return "ERROR_" + t.getClass().getSimpleName();
        }
    }

    private String readLimited(InputStream stream) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        int remaining = 512;
        int read;
        while (remaining > 0 && (read = stream.read(buffer, 0, Math.min(buffer.length, remaining))) != -1) {
            out.write(buffer, 0, read);
            remaining -= read;
        }
        return out.toString("UTF-8");
    }

    private String compact(String output) {
        if (output == null) return "";
        String text = output.trim().replaceAll("\\s+", " ");
        return text.isEmpty() ? "" : ":" + text;
    }

    /** Reserved Shizuku user-service lifecycle transaction. */
    @Override public void destroy() {
        System.exit(0);
    }
}
