package com.reed.fcmguard;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import rikka.shizuku.Shizuku;

/** Requests consent and binds a short-lived service for the one-time secure-settings grant. */
public final class AurogonShizukuManager {
    public static final int REQUEST_CODE = 42602;

    public interface Callback { void onComplete(boolean success, String detail); }

    private final Shizuku.UserServiceArgs args;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private IAurogonPermissionService service;
    private Callback pendingCallback;
    private String pendingPackage;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IAurogonPermissionService.Stub.asInterface(binder);
            grantPending();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            complete(false, "SERVICE_DISCONNECTED");
        }
    };

    public AurogonShizukuManager(Context context) {
        args = new Shizuku.UserServiceArgs(new ComponentName(context, AurogonShizukuService.class))
                .tag("fcmguard-aurogon-grant-v1")
                .version(1)
                .daemon(false)
                .processNameSuffix("fcmguard-aurogon-grant");
    }

    public static boolean isBinderReady() {
        try { return Shizuku.pingBinder(); } catch (Throwable ignored) { return false; }
    }

    public static boolean hasPermission() {
        try {
            return isBinderReady() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) { return false; }
    }

    public static RequestState requestPermission() {
        if (!isBinderReady()) return RequestState.NOT_RUNNING;
        try {
            if (Shizuku.isPreV11()) return RequestState.UNSUPPORTED;
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return RequestState.GRANTED;
            if (Shizuku.shouldShowRequestPermissionRationale()) return RequestState.DENIED;
            Shizuku.requestPermission(REQUEST_CODE);
            return RequestState.REQUESTED;
        } catch (Throwable ignored) { return RequestState.NOT_RUNNING; }
    }

    public void grantSecureSettings(String packageName, Callback callback) {
        if (!hasPermission()) { callback.onComplete(false, "PERMISSION_REQUIRED"); return; }
        if (pendingCallback != null) { callback.onComplete(false, "BUSY"); return; }
        pendingPackage = packageName;
        pendingCallback = callback;
        try { Shizuku.bindUserService(args, connection); }
        catch (Throwable error) { complete(false, "BIND_" + error.getClass().getSimpleName()); }
    }

    private void grantPending() {
        final IAurogonPermissionService active = service;
        final String packageName = pendingPackage;
        if (active == null || packageName == null || pendingCallback == null) return;
        new Thread(() -> {
            try {
                String result = active.grantSecureSettings(packageName);
                complete("OK".equals(result), result);
            } catch (RemoteException error) {
                complete(false, "REMOTE_" + error.getClass().getSimpleName());
            } catch (Throwable error) {
                complete(false, "ERROR_" + error.getClass().getSimpleName());
            }
        }, "fcmguard-aurogon-grant").start();
    }

    private void complete(boolean success, String detail) {
        Callback callback = pendingCallback;
        pendingCallback = null;
        pendingPackage = null;
        if (callback != null) mainHandler.post(() -> callback.onComplete(success, detail));
    }

    public void close() {
        try { Shizuku.unbindUserService(args, connection, true); } catch (Throwable ignored) {}
        service = null;
    }

    public enum RequestState { GRANTED, REQUESTED, DENIED, NOT_RUNNING, UNSUPPORTED }
}
