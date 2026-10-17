package com.reed.fcmguard;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Context;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** App-UID-only adapter. Settings I/O, persistence, diagnostics and broadcasts run off the UI thread. */
public final class NightFcmProtection {
    // get() converts to application context; no Activity or View is stored here.
    @SuppressLint("StaticFieldLeak")
    private static NightFcmProtection instance;
    private final Context context;
    private volatile Context strings;
    private final SharedPreferences prefs;
    private final NightProtectionEngine engine;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean enabled, cleanup, observing;
    private volatile String report = "", summary = "", logs = "";
    private volatile boolean initialized;
    public static synchronized NightFcmProtection get(Context context) {
        if (instance == null) instance = new NightFcmProtection(context.getApplicationContext());
        return instance;
    }
    private NightFcmProtection(Context context) {
        this.context = context;
        strings = LocaleHelper.apply(context);
        prefs = context.getSharedPreferences("night_fcm", Context.MODE_PRIVATE);
        // Preference loading and JSON parsing also stay on the worker.
        engine = new NightProtectionEngine(new NightProtectionEngine.Port() {
            public String read() { return Settings.System.getString(context.getContentResolver(), NightProtectionEngine.KEY); }
            public boolean write(String v) { return Settings.System.putString(context.getContentResolver(), NightProtectionEngine.KEY, v); }
            public boolean supported() { return supportedVendor(); }
            public boolean canWrite() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) return Settings.System.canWrite(context);
                return context.checkCallingOrSelfPermission(Manifest.permission.WRITE_SETTINGS)
                        == PackageManager.PERMISSION_GRANTED;
            }
            public void thaw() {
                context.sendBroadcast(new Intent("com.xiaomi.mipush.REFRESH_WHITE_LIST_ACTION")
                        .setPackage("android").putExtra("pkgName", NightProtectionEngine.GMS).putExtra("option", -1));
            }
            public void reconnect() { FcmReconnect.requestGcmReconnect(context); }
            public long now() { return System.currentTimeMillis(); }
            public void save(NightProtectionEngine.State s) { persist(s); }
        }, new NightProtectionEngine.State());
        worker.execute(() -> {
            restore(); enabled = engine.state.enabled; cleanup = engine.state.cleanup;
            initialized = true;
            engine.check(); publish();
        });
    }
    public boolean isEnabled() { return enabled; }
    public boolean cleanupPending() { return cleanup; }
    public boolean isInitialized() { return initialized; }
    public String report() { return report; }
    public String summary() { return summary; }
    public String logs() { return logs; }
    public void listen(Runnable listener) { listeners.add(listener); }
    public void unlisten(Runnable listener) { listeners.remove(listener); }
    public void observer(boolean active) {
        if (observing == active) return;
        observing = active;
        submit(() -> engine.note("observer", active ? "registered on exact key" : "stopped"));
    }
    public void whenReady(Runnable action) { worker.execute(action); }
    public void configure(boolean on) { submit(() -> engine.configure(on)); }
    public void maintain() { submit(() -> engine.maintain(true)); }
    public void repair() { submit(() -> { if (engine.state.enabled) engine.maintain(false); else engine.cleanup(false); }); }
    public void check() { submit(engine::check); }
    public void test() { submit(engine::testBroadcasts); }
    public void cleanupWithConsent() { submit(() -> { if (!engine.state.enabled) engine.cleanup(true); }); }
    public void clearLogs() { submit(engine::clearLogs); }
    public void serviceFailure(Throwable error) {
        submit(() -> { engine.state.error = "GuardService: " + error.getClass().getSimpleName() + ": " + error.getMessage(); engine.note("service",engine.state.error); });
    }
    private void submit(Runnable action) {
        worker.execute(() -> {
            try { action.run(); }
            catch (Exception e) { engine.state.error = e.getClass().getSimpleName() + ": " + e.getMessage(); }
            enabled = engine.state.enabled; cleanup = engine.state.cleanup;
            publish();
        });
    }
    private void persist(NightProtectionEngine.State s) {
        try {
            JSONObject j = new JSONObject();
            j.put("enabled",s.enabled).put("baseline",s.baselineKnown).put("preexisting",s.preexisting)
                    .put("owned",s.owned).put("uncertain",s.uncertain).put("cleanup",s.cleanup)
                    .put("lastWritten",s.lastWritten).put("lastSuccess",s.lastSuccess).put("error",s.error)
                    .put("write",s.write).put("reload",s.reload).put("thaw",s.thaw).put("reconnect",s.reconnect)
                    .put("logs",new JSONArray(s.logs));
            if (!prefs.edit().putString("state",j.toString()).commit())
                throw new IllegalStateException("Cannot persist ownership; Settings mutation aborted");
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }
    private void restore() {
        try {
            JSONObject j = new JSONObject(prefs.getString("state","{}"));
            NightProtectionEngine.State s = engine.state;
            s.enabled=j.optBoolean("enabled"); s.baselineKnown=j.optBoolean("baseline");
            s.preexisting=j.optBoolean("preexisting"); s.owned=j.optBoolean("owned");
            s.uncertain=j.optBoolean("uncertain"); s.cleanup=j.optBoolean("cleanup");
            s.lastWritten=j.has("lastWritten")?j.optString("lastWritten"):null;
            s.lastSuccess=j.optLong("lastSuccess"); s.error=j.optString("error");
            // Dispatch/verification results are historical; reset on process restart.
            JSONArray a=j.optJSONArray("logs");
            if(a!=null) for(int i=Math.max(0,a.length()-100);i<a.length();i++) s.logs.add(a.optString(i));
        } catch (Exception e) { engine.state.error="Preference restore failed: "+e; }
    }
    public void refreshLocale() { strings = LocaleHelper.apply(context); submit(() -> {}); }
    private String text(int id) { return strings.getString(id); }
    private static boolean supportedVendor() {
        String vendor = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        return vendor.contains("xiaomi") || vendor.contains("redmi") || vendor.contains("poco")
                || vendor.contains("unknown");
    }
    private String result(int status, int success, int idle) {
        return text(status==1?success:status==2?R.string.night_failed:idle);
    }
    private static String time(long millis) { return millis==0?"—":new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.ROOT).format(new Date(millis)); }
    private void publish() {
        NightProtectionEngine.State s=engine.state;
        boolean present=engine.readOk && NightProtectionEngine.contains(engine.value);
        int overall;
        if(s.cleanup) overall=R.string.night_cleanup_pending;
        else if(!s.enabled) overall=R.string.night_disabled;
        else if(!supportedVendor()) overall=R.string.night_unsupported;
        else if(!engine.permission) overall=R.string.night_permission_missing;
        else if(!engine.readOk || s.write==2) overall=R.string.night_write_failed;
        else if(!present) overall=R.string.night_detected_failure;
        else if(s.thaw==2 || s.reconnect==2 || !observing) overall=R.string.night_limited;
        else overall=R.string.night_configured;
        summary=text(overall);
        report = text(R.string.night_title)+"\n"+time(System.currentTimeMillis())
                +"\nAndroid API: "+Build.VERSION.SDK_INT+"\nROM / HyperOS (unverified build label): "+Build.DISPLAY+" / "+Build.VERSION.INCREMENTAL
                +"\nManufacturer: "+Build.MANUFACTURER+"\n"+text(R.string.night_experimental)
                +"\n"+text(R.string.night_permission)+": "+text(engine.permission?R.string.night_granted:R.string.night_denied)
                +"\n"+text(R.string.night_read)+": "+text(engine.readOk?R.string.night_success:R.string.night_failed)
                +"\n"+text(R.string.night_membership)+": "+text(!engine.readOk?R.string.night_unknown:present?R.string.night_yes:R.string.night_no)
                +"\n"+NightProtectionEngine.KEY+" = "+(engine.readOk?String.valueOf(engine.value):"UNKNOWN")
                +"\n"+text(R.string.night_write)+": "+result(s.write,R.string.night_success,R.string.night_not_tested)
                +"\n"+text(R.string.night_observer)+": "+text(observing?R.string.night_active:R.string.night_inactive)
                +"\n"+text(R.string.night_reload)+": "+result(s.reload,R.string.night_triggered,R.string.night_not_executed)
                +"\n"+text(R.string.night_thaw)+": "+result(s.thaw,R.string.night_sent,R.string.night_not_executed)
                +"\n"+text(R.string.night_frozen)+": "+text(R.string.night_unknown)
                +"\n"+text(R.string.night_reconnect)+": "+result(s.reconnect,R.string.night_sent,R.string.night_not_executed)
                +"\nFCM Socket: "+text(R.string.night_cannot_check)
                +"\n"+text(R.string.night_overall)+": "+summary
                +"\n"+text(R.string.night_last_success)+": "+time(s.lastSuccess)
                +"\n"+text(R.string.night_error)+": "+s.error
                +"\n"+text(R.string.night_ownership)+": baseline="+s.baselineKnown+", preexisting="+s.preexisting
                +", added="+s.owned+", uncertain="+s.uncertain+", cleanupPending="+s.cleanup
                +"\n"+text(R.string.night_warning);
        StringBuilder b=new StringBuilder();
        for(String line:s.logs) {
            int split=line.indexOf(" | ");
            if(split>0) { try { line=time(Long.parseLong(line.substring(0,split)))+line.substring(split); } catch(NumberFormatException ignored) {} }
            b.append(line).append('\n');
        }
        logs=b.toString();
        main.post(() -> { for(Runnable listener:listeners) listener.run(); });
    }
}
