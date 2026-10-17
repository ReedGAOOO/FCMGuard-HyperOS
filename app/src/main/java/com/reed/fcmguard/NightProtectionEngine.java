package com.reed.fcmguard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Platform-free state machine. All calls are serialized by NightFcmProtection's worker. */
public final class NightProtectionEngine {
    public static final String KEY = "com.miui.game.allowlist";
    public static final String GMS = "com.google.android.gms";
    public interface Port {
        String read() throws Exception;
        boolean write(String value) throws Exception;
        boolean canWrite();
        default boolean supported() { return true; }
        void thaw() throws Exception;
        void reconnect() throws Exception;
        long now();
        void save(State state); // Must durably save ownership BEFORE any Settings write.
    }
    public static final class State {
        public boolean enabled, baselineKnown, preexisting, owned, uncertain, cleanup;
        public String lastWritten;
        public long lastSuccess;
        public String error = "";
        public int write, reload, thaw, reconnect; // 0 not tested, 1 dispatched/verified, 2 failed
        public final List<String> logs = new ArrayList<>();
    }
    public final State state;
    private final Port port;
    private boolean initialize = true;
    private long nextAttempt;
    private long lastBroadcast;
    private boolean recoveryPending;
    public String value;
    public boolean readOk, permission;
    public NightProtectionEngine(Port port, State state) { this.port = port; this.state = state; }
    public static boolean contains(String raw) {
        return raw != null && Arrays.asList(raw.split("_")).contains(GMS);
    }
    public static String append(String raw) {
        if (contains(raw)) return raw;
        return raw == null || raw.isEmpty() ? GMS : raw + (raw.endsWith("_") ? "" : "_") + GMS;
    }
    public static String reload(String raw) {
        if (!contains(raw)) throw new IllegalArgumentException("GMS absent");
        return raw.endsWith("_") ? raw.substring(0, raw.length()-1) : raw + "_";
    }
    public static String remove(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder();
        boolean first = true;
        boolean nonempty = false;
        for (String part : raw.split("_", -1)) {
            if (GMS.equals(part)) continue;
            if (!first) out.append('_');
            out.append(part);
            first = false;
            nonempty |= !part.isEmpty();
        }
        return nonempty ? out.toString() : "";
    }
    private void log(String action, String result, boolean automatic) {
        state.logs.add(port.now() + " | " + action + " | " + result + " | auto=" + automatic
                + " | thawSent=" + (action.equals("thaw") && result.startsWith("dispatched"))
                + " | reconnectSent=" + (action.equals("reconnect") && result.startsWith("dispatched")));
        while (state.logs.size() > 100) state.logs.remove(0);
    }
    private void fail(String step, Exception e, boolean automatic) {
        state.error = step + ": " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
        nextAttempt = port.now() + 60_000; // Never spin on observer/self notifications after a failure.
        log(step, state.error, automatic);
    }
    public void inspect() { inspect(false); }
    private void inspect(boolean automatic) {
        readOk = false;
        permission = false;
        try {
            permission = port.canWrite();
            value = port.read(); readOk = true;
            if (state.owned && contains(value) && state.lastWritten != null
                    && !Objects.equals(value, state.lastWritten)) state.uncertain = true;
        } catch (Exception e) { readOk = false; fail("read", e, automatic); }
    }
    public void configure(boolean enabled) {
        if (enabled && !state.enabled && state.cleanup) {
            fail("enable", new IllegalStateException("Cleanup pending; resolve before enabling"), false);
            port.save(state); return;
        }
        if (enabled && !state.enabled) initialize = true;
        state.enabled = enabled;
        if (!enabled) {
            state.cleanup = state.owned;
            if (!state.owned) state.baselineKnown = false;
        }
        log(enabled ? "enable" : "disable", enabled ? "requested; ordinary app UID" : "monitoring disabled; cleanup evaluated", false);
        port.save(state);
        maintain(false);
    }
    public void maintain(boolean automatic) {
        inspect(automatic);
        if (!readOk) { port.save(state); return; }
        if (automatic && port.now() < nextAttempt) return;
        if (!state.enabled) {
            if (state.cleanup && !automatic) cleanup(false);
            return;
        }
        boolean attempted = false;
        try {
            if (!port.supported()) throw new UnsupportedOperationException("Not a Xiaomi / Redmi / POCO system");
            if (!permission) throw new SecurityException("WRITE_SETTINGS not granted");
            if (!state.baselineKnown) {
                state.preexisting = contains(value); state.baselineKnown = true;
                port.save(state);
            }
            boolean missing = !contains(value);
            if (!missing && !initialize) {
                if (recoveryPending) broadcasts(automatic);
                port.save(state); return;
            }
            log("check", missing ? "GMS missing; WRITE_SETTINGS granted" : "GMS present; initialization reload needed", automatic);
            String expected = value;
            String candidate = missing ? append(expected) : reload(expected);
            if (missing && !state.preexisting) {
                state.owned = true;
                // Save before mutation: a process death must not forget a possible added entry.
                port.save(state);
            }
            state.write = 0;
            if (!missing) state.reload = 0;
            attempted = true;
            log("write", "attempting stable Settings PUT and read-back", automatic);
            writeStable(expected, candidate);
            state.write = 1; state.reload = 1;
            initialize = false;
            state.lastSuccess = port.now(); state.error = "";
            log("repair", "write verified; framework notification requested, cache unverified", automatic);
            recoveryPending = true;
            broadcasts(automatic);
        } catch (Exception e) {
            state.write = 2;
            if (attempted) state.reload = 2;
            fail("repair", e, automatic);
        }
        port.save(state);
    }
    private void writeStable(String expected, String candidate) throws Exception {
        if (!Objects.equals(expected, port.read())) {
            state.uncertain |= state.owned;
            throw new IllegalStateException("Concurrent writer before write; deferred");
        }
        // No CAS exists: a writer can still race between this read and PUT.
        String previousWritten = state.lastWritten;
        state.lastWritten = candidate; port.save(state);
        if (!port.write(candidate)) {
            state.lastWritten = previousWritten; port.save(state);
            throw new IllegalStateException("SettingsProvider rejected PUT");
        }
        value = port.read(); readOk = true;
        if (!Objects.equals(candidate, value)) {
            state.uncertain |= state.owned;
            throw new IllegalStateException("Read-back differs; concurrent writer or rejected value");
        }
    }
    public void cleanup(boolean explicitConsent) {
        inspect();
        if (!readOk) { port.save(state); return; }
        try {
            if (!state.cleanup || state.preexisting) return;
            if (state.uncertain && !explicitConsent)
                throw new IllegalStateException("Ownership uncertain; explicit cleanup consent required");
            if (!permission) throw new SecurityException("WRITE_SETTINGS not granted; cleanup pending");
            String candidate = remove(value);
            // Empty PUT, never delete: a null value can leave Xiaomi's runtime cache intact.
            if (!Objects.equals(value, candidate)) writeStable(value, candidate);
            state.write = 1; state.reload = 1;
            state.cleanup = false; state.owned = false; state.baselineKnown = false;
            state.uncertain = false; state.lastWritten = null; state.error = "";
            log("disable", "cleanup verified; cache unverified", false);
        } catch (Exception e) { state.write = 2; fail("cleanup", e, false); }
        finally { port.save(state); }
    }
    public void testBroadcasts() { broadcasts(false); port.save(state); }
    private void broadcasts(boolean automatic) {
        if (lastBroadcast != 0 && port.now() - lastBroadcast < 10_000) {
            log("broadcast", "rate limited; retry manually after 10 seconds", automatic); return;
        }
        lastBroadcast = port.now();
        try { port.thaw(); state.thaw = 1; log("thaw", "dispatched; actual thaw UNKNOWN", automatic); }
        catch (Exception e) { state.thaw = 2; fail("thaw", e, automatic); }
        try { port.reconnect(); state.reconnect = 1; log("reconnect", "dispatched; socket UNKNOWN", automatic); }
        catch (Exception e) { state.reconnect = 2; fail("reconnect", e, automatic); }
        recoveryPending = state.thaw == 2 || state.reconnect == 2;
        if (!recoveryPending && (state.error.startsWith("thaw:") || state.error.startsWith("reconnect:")))
            state.error = "";
    }
    public void check() { inspect(); log("check", readOk ? "read OK; no mutation" : "read failed", false); port.save(state); }
    public void note(String action, String result) { log(action, result, false); port.save(state); }
    public void clearLogs() { state.logs.clear(); port.save(state); }
}
