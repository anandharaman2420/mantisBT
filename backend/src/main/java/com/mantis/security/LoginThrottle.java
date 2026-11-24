package com.mantis.security;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Counts failed attempts per key (user, address, ...) and locks the key when too many happen in a time window.
 * Every further lock doubles in length up to {@code maxLockMillis} (15 min, 30 min, 1 h ...); a success clears the key.
 * Plain Java with an injectable clock so it can be unit tested without Spring. State is in memory (per instance).
 */
public final class LoginThrottle {
    private static final int MAX_ENTRIES = 50_000;

    private final int maxFailures;
    private final long windowMillis;
    private final long baseLockMillis;
    private final long maxLockMillis;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    private static final class Entry {
        int failures;
        long windowStart;
        long lockedUntil;
        int lockouts;
        long lastActivity;
    }

    public LoginThrottle(int maxFailures, long windowMillis, long baseLockMillis, long maxLockMillis, LongSupplier clock) {
        if (maxFailures < 1) throw new IllegalArgumentException("maxFailures must be >= 1");
        this.maxFailures = maxFailures;
        this.windowMillis = windowMillis;
        this.baseLockMillis = baseLockMillis;
        this.maxLockMillis = Math.max(baseLockMillis, maxLockMillis);
        this.clock = clock;
    }

    /** @return 0 when an attempt is allowed, otherwise the number of seconds (rounded up) until it is */
    public long retryAfterSeconds(String key) {
        Entry e = entries.get(key);
        if (e == null) return 0;
        synchronized (e) {
            long now = clock.getAsLong();
            return e.lockedUntil > now ? (e.lockedUntil - now + 999) / 1000 : 0;
        }
    }

    public void failure(String key) {
        if (entries.size() > MAX_ENTRIES) prune();
        Entry e = entries.computeIfAbsent(key, k -> new Entry());
        synchronized (e) {
            long now = clock.getAsLong();
            e.lastActivity = now;
            if (e.lockedUntil > now) return;                         // already locked: do not extend the lock
            if (now - e.windowStart > windowMillis) {                // old failures expired -> new window
                e.failures = 0;
                e.windowStart = now;
            }
            if (++e.failures >= maxFailures) {
                long lock = Math.min(baseLockMillis << Math.min(e.lockouts, 20), maxLockMillis);
                e.lockedUntil = now + lock;
                e.lockouts++;
                e.failures = 0;
                e.windowStart = now;
            }
        }
    }

    /** a correct password clears the counters and the escalation of this key */
    public void success(String key) { entries.remove(key); }

    /** drops keys that are neither locked nor recently active (keeps escalation memory for the longest lock time) */
    private void prune() {
        long now = clock.getAsLong();
        entries.entrySet().removeIf(en -> {
            Entry e = en.getValue();
            synchronized (e) {
                long idle = now - e.lastActivity;
                return e.lockedUntil <= now && idle > (e.lockouts > 0 ? maxLockMillis : windowMillis);
            }
        });
    }

    int size() { return entries.size(); }
}
