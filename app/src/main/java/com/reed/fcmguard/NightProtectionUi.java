package com.reed.fcmguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** View bindings only; the process controller never retains this Activity. */
final class NightProtectionUi {
    private final Activity activity;
    private final NightFcmProtection guard;
    private final Runnable serviceChanged;
    private final Switch toggle;
    private boolean binding, lastEnabled, first = true;
    private final Runnable listener = this::render;
    NightProtectionUi(Activity activity, Runnable serviceChanged) {
        this.activity = activity; this.serviceChanged = serviceChanged;
        guard = NightFcmProtection.get(activity);
        guard.refreshLocale();
        toggle = activity.findViewById(R.id.nightSwitch);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (!binding) { toggle.setEnabled(false); guard.configure(checked); }
        });
        activity.findViewById(R.id.nightCheck).setOnClickListener(v -> guard.check());
        activity.findViewById(R.id.nightRepair).setOnClickListener(v -> guard.repair());
        activity.findViewById(R.id.nightTest).setOnClickListener(v -> guard.test());
        activity.findViewById(R.id.nightCopy).setOnClickListener(v -> copy(guard.report()+"\n\n"+guard.logs()));
        activity.findViewById(R.id.nightCopyLogs).setOnClickListener(v -> copy(guard.logs()));
        activity.findViewById(R.id.nightClearLogs).setOnClickListener(v -> guard.clearLogs());
        activity.findViewById(R.id.nightDebug).setOnClickListener(v -> {
            View details=activity.findViewById(R.id.nightDetails);
            details.setVisibility(details.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);
        });
        activity.findViewById(R.id.nightCleanup).setOnClickListener(v -> new AlertDialog.Builder(activity)
                .setMessage(R.string.night_cleanup_confirm).setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(android.R.string.ok,(d,w) -> guard.cleanupWithConsent()).show());
    }
    void resume() { guard.listen(listener); guard.check(); render(); }
    void pause() { guard.unlisten(listener); }
    private void render() {
        if(activity.isFinishing() || activity.isDestroyed()) return;
        binding=true; toggle.setChecked(guard.isEnabled()); toggle.setEnabled(guard.isInitialized()); binding=false;
        ((TextView)activity.findViewById(R.id.nightSummary)).setText(guard.summary());
        ((TextView)activity.findViewById(R.id.nightReport)).setText(guard.report());
        ((TextView)activity.findViewById(R.id.nightLogs)).setText(guard.logs());
        activity.findViewById(R.id.nightCleanup).setVisibility(guard.cleanupPending()?View.VISIBLE:View.GONE);
        if(guard.isInitialized() && (first || lastEnabled!=guard.isEnabled())) {
            first=false; lastEnabled=guard.isEnabled(); serviceChanged.run();
        }
    }
    private void copy(String text) {
        ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if(clipboard!=null) { clipboard.setPrimaryClip(ClipData.newPlainText("Night FCM diagnostics",text));
            Toast.makeText(activity,R.string.night_copied,Toast.LENGTH_SHORT).show(); }
    }
}
