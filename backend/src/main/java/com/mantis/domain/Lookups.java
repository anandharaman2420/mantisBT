package com.mantis.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Mantis "enum" strings (config g_status_enum_string, g_priority_enum_string ...) as Java constants.
 * Values are the SAME integers MantisBT stores in its database, so existing data can be imported as-is.
 */
public final class Lookups {
    private Lookups() {}

    // access levels
    public static final int ANYBODY = 0, VIEWER = 10, REPORTER = 25, UPDATER = 40, DEVELOPER = 55,
            MANAGER = 70, ADMINISTRATOR = 90;
    // status
    public static final int NEW = 10, FEEDBACK = 20, ACKNOWLEDGED = 30, CONFIRMED = 40, ASSIGNED = 50,
            RESOLVED = 80, CLOSED = 90;
    // resolution
    public static final int RES_OPEN = 10, RES_FIXED = 20, RES_REOPENED = 30;
    // view state
    public static final int VS_PUBLIC = 10, VS_PRIVATE = 50;
    // history types
    public static final int H_NORMAL = 0, H_NEW_BUG = 1, H_NOTE_ADDED = 2, H_NOTE_UPDATED = 3, H_FILE_ADDED = 9,
            H_FILE_DELETED = 10, H_MONITOR = 12, H_UNMONITOR = 13, H_REL_ADDED = 18, H_REL_DELETED = 19,
            H_TAG_ATTACHED = 25, H_TAG_DETACHED = 26;

    public static final Map<Integer, String> ACCESS = map(0, "anybody", 10, "viewer", 25, "reporter", 40, "updater",
            55, "developer", 70, "manager", 90, "administrator");
    public static final Map<Integer, String> STATUS = map(10, "new", 20, "feedback", 30, "acknowledged",
            40, "confirmed", 50, "assigned", 80, "resolved", 90, "closed");
    public static final Map<Integer, String> RESOLUTION = map(10, "open", 20, "fixed", 30, "reopened",
            40, "unable to reproduce", 50, "not fixable", 60, "duplicate", 70, "no change required",
            80, "suspended", 90, "won't fix");
    public static final Map<Integer, String> PRIORITY = map(10, "none", 20, "low", 30, "normal", 40, "high",
            50, "urgent", 60, "immediate");
    public static final Map<Integer, String> SEVERITY = map(10, "feature", 20, "trivial", 30, "text", 40, "tweak",
            50, "minor", 60, "major", 70, "crash", 80, "block");
    public static final Map<Integer, String> REPRODUCIBILITY = map(10, "always", 30, "sometimes", 50, "random",
            70, "have not tried", 90, "unable to reproduce", 100, "N/A");
    public static final Map<Integer, String> PROJECTION = map(10, "none", 30, "tweak", 50, "minor fix",
            70, "major rework", 90, "redesign");
    public static final Map<Integer, String> ETA = map(10, "none", 20, "< 1 day", 30, "2-3 days", 40, "< 1 week",
            50, "< 1 month", 60, "> 1 month");
    public static final Map<Integer, String> PROJECT_STATUS = map(10, "development", 30, "release", 50, "stable",
            70, "obsolete");
    public static final Map<Integer, String> VIEW_STATE = map(10, "public", 50, "private");
    public static final Map<Integer, String> NOTE_TYPE = map(0, "note", 1, "reminder", 2, "time tracking");
    public static final Map<Integer, String> RELATIONSHIP = map(0, "duplicate of", 1, "related to",
            2, "parent of", 3, "child of", 4, "has duplicate");

    private static Map<Integer, String> map(Object... kv) {
        Map<Integer, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((Integer) kv[i], (String) kv[i + 1]);
        return java.util.Collections.unmodifiableMap(m);
    }

    public static String label(Map<Integer, String> m, int v) { return m.getOrDefault(v, String.valueOf(v)); }
}
