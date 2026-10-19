package com.reed.fcmguard;

import android.os.RemoteException;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Minimal Shizuku user service used only for the explicit WRITE_SECURE_SETTINGS grant. */
public final class AurogonShizukuService extends IAurogonPermissionService.Stub {
    @Override public String grantSecureSettings(String packageName) throws RemoteException {
        if (!"com.reed.fcmguard".equals(packageName)) {
            return "INVALID_PACKAGE";
        }
        try {
            Process process = new ProcessBuilder("pm", "grant", packageName,
                    "android.permission.WRITE_SECURE_SETTINGS").redirectErrorStream(true).start();
            String output = readLimited(process.getInputStream());
            int exit = process.waitFor();
            return exit == 0 ? "OK" : "EXIT_" + exit + compact(output);
        } catch (Throwable error) {
            return "ERROR_" + error.getClass().getSimpleName();
        }
    }

    private String readLimited(InputStream stream) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        int remaining = 4096;
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

    @Override public void destroy() { System.exit(0); }
}
