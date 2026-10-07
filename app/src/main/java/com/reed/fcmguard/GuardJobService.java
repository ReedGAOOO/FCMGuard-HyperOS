package com.reed.fcmguard;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;

/**
 * Quiet-mode check: repair once to catch overwrites that happened while the
 * process was dead, listen briefly for the rewrites that usually follow, then
 * release the process entirely. The next period is owned by JobScheduler, so
 * HyperOS killing this process costs the protection nothing.
 */
public class GuardJobService extends JobService {
    private static final long LISTEN_WINDOW_MS = 90L * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private JobParameters params;
    private WhitelistWatcher watcher;
    private boolean finished;

    private final Runnable endWindow = new Runnable() {
        @Override public void run() {
            finishWork();
        }
    };

    @Override public boolean onStartJob(final JobParameters params) {
        this.params = params;
        this.finished = false;

        if (!SettingsGuard.isProtectionEnabled(this)) {
            SmartGuardController.cancelJob(this);
            return false;
        }
        if (SmartGuardController.shouldWatchResidently(this)) {
            // Stale period fired while vigilant mode owns watching.
            SmartGuardController.startGuardService(this);
            return false;
        }

        SmartGuardController.checkDowngrade(this);

        watcher = new WhitelistWatcher(this, handler, new WhitelistWatcher.Listener() {
            @Override public void onRepair(boolean changed) {
                if (changed) {
                    SmartGuardController.recordBreak(GuardJobService.this);
                    handler.post(endWindow);
                }
            }
        });

        // Repair immediately, then hold a short listening window before dying.
        watcher.repairNow();
        watcher.register();
        handler.postDelayed(endWindow, LISTEN_WINDOW_MS);
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        // The system cancels the job itself; only release our resources here.
        handler.removeCallbacksAndMessages(null);
        if (watcher != null) {
            watcher.unregister();
            watcher = null;
        }
        finished = true;
        return false;
    }

    private void finishWork() {
        handler.removeCallbacksAndMessages(null);
        if (watcher != null) {
            watcher.unregister();
            watcher = null;
        }
        if (!finished && params != null) {
            finished = true;
            jobFinished(params, false);
        }
    }
}
