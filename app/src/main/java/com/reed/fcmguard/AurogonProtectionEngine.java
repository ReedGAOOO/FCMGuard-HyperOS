package com.reed.fcmguard;

import java.util.List;
import java.util.Objects;

/** Read/merge/write/read-back for Aurogon settings, with no Settings CAS assumption. */
public final class AurogonProtectionEngine {
    public interface Port {
        String read() throws Exception;
        boolean write(String value) throws Exception;
    }

    public static final class Result {
        public String original;
        public String finalValue;
        public String error = "";
        public boolean readSucceeded, writeAttempted, writeReturned, verified, changed;
    }

    private final Port port;
    public AurogonProtectionEngine(Port port) { this.port = port; }

    public Result sync(List<String> previouslyManaged, List<String> enabledPackages) {
        Result r = new Result();
        try {
            String original = port.read();
            r.original = original; r.readSucceeded = true;
            String candidate = AurogonConfig.mergeAutostartRules(original, previouslyManaged, enabledPackages);
            if (Objects.equals(original, candidate)) {
                r.finalValue = original; r.verified = true; return r;
            }
            String beforeWrite = port.read();
            if (!Objects.equals(original, beforeWrite)) {
                r.finalValue = beforeWrite;
                r.error = "Concurrent Settings.Global writer detected before PUT; no write attempted";
                return r;
            }
            r.writeAttempted = true;
            r.writeReturned = port.write(candidate);
            r.finalValue = port.read();
            r.changed = !Objects.equals(original, r.finalValue);
            r.verified = r.writeReturned && Objects.equals(candidate, r.finalValue);
            if (!r.writeReturned) r.error = "Settings.Global.putString returned false";
            else if (!r.verified) r.error = "Read-back differs from candidate";
        } catch (Exception e) {
            r.error = e.getClass().getName() + ": " + String.valueOf(e.getMessage());
            try { r.finalValue = port.read(); } catch (Exception ignored) {}
        }
        return r;
    }
}
