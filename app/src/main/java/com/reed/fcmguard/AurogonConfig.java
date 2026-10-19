package com.reed.fcmguard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Merge helpers for Xiaomi's semicolon-separated Aurogon broadcast rules. */
public final class AurogonConfig {
    public static final String KEY = "aurogon_enable";
    public static final String FCM_RECEIVE = "com.google.android.c2dm.intent.RECEIVE";
    public static final String MANAGER = "com.reed.fcmguard";
    public static final String MARKER_ACTION = "__FCMGUARD_AUROGON_AUTOSTART_V1__";
    private static final String PREFIX = "broadcastctrl:";

    private AurogonConfig() {}

    /** Updates only the section owned by this feature and leaves every other section untouched. */
    public static String mergeAutostartRules(String raw, List<String> previouslyManaged,
                                             List<String> enabledPackages) {
        List<String> sections = split(normalize(raw));
        List<String> retained = new ArrayList<>();
        String managed = null;
        for (String section : sections) {
            if (isManaged(section)) managed = section;
            else retained.add(section);
        }

        Set<String> requested = new LinkedHashSet<>();
        if (enabledPackages != null) {
            for (String packageName : enabledPackages) {
                if (isPackageName(packageName)) requested.add(packageName);
            }
        }
        List<String> preserved = new ArrayList<>();
        if (managed != null) {
            String[] previousTokens = managed.split("#", -1);
            for (int i = 1; i < previousTokens.length; i++) {
                String token = previousTokens[i];
                int slash = token.indexOf('/');
                // The marker section is exclusively maintained by this feature. Rebuild
                // its FCM receive rules from the current scan even if an older installation
                // has no ownership snapshot, so stale/non-FCM entries cannot survive forever.
                if (slash > 0) {
                    String[] actions = token.substring(slash + 1).split(",", -1);
                    List<String> keep = new ArrayList<>();
                    for (String action : actions) if (!FCM_RECEIVE.equals(action)) keep.add(action);
                    if (keep.isEmpty()) continue;
                    token = token.substring(0, slash + 1) + join(",", keep);
                }
                if (!token.equals(MANAGER + "/" + MARKER_ACTION) && !preserved.contains(token)) preserved.add(token);
            }
        }
        if (requested.isEmpty()) {
            if (!preserved.isEmpty()) retained.add("broadcastctrl:true#" + MANAGER + "/" + MARKER_ACTION
                    + "#" + join("#", preserved));
            return join(";", retained);
        }
        List<String> tokens = new ArrayList<>();
        tokens.add(MANAGER + "/" + MARKER_ACTION);
        tokens.addAll(preserved);
        for (String packageName : requested) {
            if (!hasFcmRule(retained, packageName) && !hasTokenFcmRule(preserved, packageName))
                tokens.add(packageName + "/" + FCM_RECEIVE);
        }
        // Always keep an explicit marker section while the maintenance feature is active.
        String updatedManaged = "broadcastctrl:true#" + join("#", tokens);
        retained.add(updatedManaged);
        return join(";", retained);
    }

    public static boolean hasFcmRule(String raw, String packageName) {
        return hasFcmRule(split(normalize(raw)), packageName);
    }

    private static boolean hasFcmRule(List<String> sections, String packageName) {
        for (String section : sections) {
            String[] tokens = section.split("#", -1);
            if (tokens.length == 0) continue;
            String header = tokens[0].trim();
            if (header.startsWith(PREFIX) && !(PREFIX + "true").equals(header)) continue;
            for (int i = 1; i < tokens.length; i++) {
                int slash = tokens[i].indexOf('/');
                if (slash <= 0 || !packageName.equals(tokens[i].substring(0, slash))) continue;
                String actions = tokens[i].substring(slash + 1);
                if ("ALL".equals(actions)) return true;
                for (String action : actions.split(",", -1)) if (FCM_RECEIVE.equals(action)) return true;
            }
        }
        return false;
    }

    private static boolean hasTokenFcmRule(List<String> tokens, String packageName) {
        for (String token : tokens) {
            int slash = token.indexOf('/');
            if (slash <= 0 || !packageName.equals(token.substring(0, slash))) continue;
            String actions = token.substring(slash + 1);
            if ("ALL".equals(actions)) return true;
            for (String action : actions.split(",", -1)) if (FCM_RECEIVE.equals(action)) return true;
        }
        return false;
    }

    public static boolean isManaged(String section) {
        String[] tokens = section.split("#", -1);
        String marker = MANAGER + "/" + MARKER_ACTION;
        for (int i = 1; i < tokens.length; i++) if (marker.equals(tokens[i])) return true;
        return false;
    }

    public static List<String> managedPackages(String raw) {
        Set<String> packages = new LinkedHashSet<>();
        for (String section : split(normalize(raw))) {
            if (!isManaged(section)) continue;
            for (String token : section.split("#", -1)) {
                int slash = token.indexOf('/');
                if (slash <= 0) continue;
                String packageName = token.substring(0, slash);
                String actions = token.substring(slash + 1);
                boolean receivesFcm = false;
                for (String action : actions.split(",", -1)) {
                    if (FCM_RECEIVE.equals(action) || "ALL".equals(action)) receivesFcm = true;
                }
                if (isPackageName(packageName) && receivesFcm) packages.add(packageName);
            }
        }
        return new ArrayList<>(packages);
    }

    /** Package tokens present in enabled Aurogon broadcast-control sections. */
    public static List<String> configuredPackages(String raw) {
        Set<String> packages = new LinkedHashSet<>();
        String marker = MANAGER + "/" + MARKER_ACTION;
        for (String section : split(normalize(raw))) {
            String[] tokens = section.split("#", -1);
            if (tokens.length < 2 || !(PREFIX + "true").equals(tokens[0].trim())) continue;
            for (int i = 1; i < tokens.length; i++) {
                String token = tokens[i].trim();
                if (marker.equals(token)) continue;
                int slash = token.indexOf('/');
                if (slash <= 0) continue;
                String packageName = token.substring(0, slash);
                if (isPackageName(packageName)) packages.add(packageName);
            }
        }
        return new ArrayList<>(packages);
    }

    public static List<String> intersectAutostartAppOps(String primary, String secondary) {
        Set<String> first = parsePackages(primary);
        Set<String> second = parsePackages(secondary);
        List<String> result = new ArrayList<>();
        for (String packageName : first) if (second.contains(packageName)) result.add(packageName);
        return result;
    }

    /** Retains existing rules for apps whose Autostart state cannot be read, without adding new ones. */
    public static List<String> includeManagedUnknownPackages(List<String> enabledPackages,
                                                              List<String> unknownPackages,
                                                              List<String> previouslyManaged) {
        Set<String> managed = new LinkedHashSet<>();
        if (previouslyManaged != null) managed.addAll(previouslyManaged);
        Set<String> result = new LinkedHashSet<>();
        if (enabledPackages != null) {
            for (String packageName : enabledPackages) if (isPackageName(packageName)) result.add(packageName);
        }
        if (unknownPackages != null) {
            for (String packageName : unknownPackages) {
                if (isPackageName(packageName) && managed.contains(packageName)) result.add(packageName);
            }
        }
        return new ArrayList<>(result);
    }

    private static Set<String> parsePackages(String output) {
        Set<String> result = new LinkedHashSet<>();
        if (output == null) return result;
        for (String line : output.split("\\r?\\n")) {
            String value = line.trim();
            if (isPackageName(value)) result.add(value);
        }
        return result;
    }

    private static boolean isPackageName(String value) {
        return value != null && value.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    }

    private static List<String> split(String value) {
        List<String> result = new ArrayList<>();
        if (value.isEmpty()) return result;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == ';') {
                result.add(value.substring(start, i));
                start = i + 1;
            }
        }
        result.add(value.substring(start));
        return result;
    }

    private static String normalize(String value) {
        if (value == null || "null".equals(value)) return "";
        return value.replaceFirst("[\\r\\n]+$", "");
    }

    private static String join(String separator, List<String> values) {
        StringBuilder result = new StringBuilder();
        boolean first = true;
        for (String value : values) {
            if (!first) result.append(separator);
            result.append(value);
            first = false;
        }
        return result.toString();
    }
}
