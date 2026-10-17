package com.reed.fcmguard;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;

import rikka.shizuku.Shizuku;

/** Main-process adapter for the optional Shizuku Autostart writer. */
public final class ShizukuAutostartManager {
    public static final int REQUEST_CODE = 42601;

    public interface Callback {
        void onComplete(boolean success, String detail);
    }

    private final Shizuku.UserServiceArgs args;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private IShizukuAutostartService service;
    private String pendingPackage;
    private boolean pendingEnabled;
    private Callback pendingCallback;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IShizukuAutostartService.Stub.asInterface(binder);
            runPending();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            completePending(false, "SERVICE_DISCONNECTED");
        }
    };

    public ShizukuAutostartManager(Context context) {
        args = new Shizuku.UserServiceArgs(new ComponentName(context, ShizukuAutostartService.class))
                .tag("fcmguard-autostart-v1")
                .version(1)
                .daemon(false)
                .processNameSuffix("fcmguard-autostart");
    }

    public static boolean isBinderReady() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean hasPermission() {
        try {
            return isBinderReady() && !Shizuku.isPreV11() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Requests explicit Shizuku consent when possible; the Activity owns the result listener. */
    public static RequestState requestPermission() {
        if (!isBinderReady()) return RequestState.NOT_RUNNING;
        try {
            if (Shizuku.isPreV11()) return RequestState.UNSUPPORTED;
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                return RequestState.GRANTED;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) return RequestState.DENIED;
            Shizuku.requestPermission(REQUEST_CODE);
            return RequestState.REQUESTED;
        } catch (Throwable ignored) {
            return RequestState.NOT_RUNNING;
        }
    }

    public void setAutostart(String packageName, boolean enabled, Callback callback) {
        if (!hasPermission()) {
            callback.onComplete(false, "PERMISSION_REQUIRED");
            return;
        }
        if (pendingCallback != null) {
            callback.onComplete(false, "BUSY");
            return;
        }
        pendingPackage = packageName;
        pendingEnabled = enabled;
        pendingCallback = callback;
        if (service != null) {
            runPending();
            return;
        }
        try {
            Shizuku.bindUserService(args, connection);
        } catch (Throwable t) {
            completePending(false, "BIND_" + t.getClass().getSimpleName());
        }
    }

    private void runPending() {
        final IShizukuAutostartService active = service;
        final String packageName = pendingPackage;
        final boolean enabled = pendingEnabled;
        if (active == null || packageName == null || pendingCallback == null) return;
        new Thread(() -> {
            try {
                String result = active.setAutostart(packageName, enabled);
                completePending("OK".equals(result), result);
            } catch (RemoteException e) {
                completePending(false, "REMOTE_" + e.getClass().getSimpleName());
            } catch (Throwable t) {
                completePending(false, "ERROR_" + t.getClass().getSimpleName());
            }
        }, "fcmguard-shizuku-autostart").start();
    }

    private void completePending(boolean success, String detail) {
        Callback callback = pendingCallback;
        pendingCallback = null;
        pendingPackage = null;
        if (callback != null) mainHandler.post(() -> callback.onComplete(success, detail));
    }

    public void close() {
        try {
            Shizuku.unbindUserService(args, connection, true);
        } catch (Throwable ignored) {}
        service = null;
    }

    public enum RequestState { GRANTED, REQUESTED, DENIED, NOT_RUNNING, UNSUPPORTED }
}
