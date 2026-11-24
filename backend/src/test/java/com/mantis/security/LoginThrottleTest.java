package com.mantis.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Pure unit tests (no Spring context): time is controlled by a fake clock. */
class LoginThrottleTest {
    private static final long MIN = 60_000L;
    private final AtomicLong now = new AtomicLong(1_000_000_000L);

    private LoginThrottle throttle() { return new LoginThrottle(5, 15 * MIN, 15 * MIN, 24 * 60 * MIN, now::get); }
    private LoginAttemptService service() { return new LoginAttemptService(true, 5, 15, 15, now::get); }

    @BeforeEach void reset() { now.set(1_000_000_000L); }

    private void fail(LoginThrottle t, String key, int times) { for (int i = 0; i < times; i++) t.failure(key); }

    // ---------------------------------------------------------------- LoginThrottle
    @Test void lockedOnlyAfterMaxFailures() {
        LoginThrottle t = throttle();
        fail(t, "a", 4);
        assertEquals(0, t.retryAfterSeconds("a"));
        t.failure("a");
        assertEquals(15 * 60, t.retryAfterSeconds("a"));
    }

    @Test void lockExpires() {
        LoginThrottle t = throttle();
        fail(t, "a", 5);
        now.addAndGet(15 * MIN - 1);
        assertTrue(t.retryAfterSeconds("a") > 0);
        now.addAndGet(1);
        assertEquals(0, t.retryAfterSeconds("a"));
    }

    @Test void retryAfterIsRoundedUp() {
        LoginThrottle t = throttle();
        fail(t, "a", 5);
        now.addAndGet(15 * MIN - 1500);
        assertEquals(2, t.retryAfterSeconds("a"));
    }

    @Test void everyLockIsTwiceAsLongUntilTheCap() {
        LoginThrottle t = throttle();
        fail(t, "a", 5);
        assertEquals(15 * 60, t.retryAfterSeconds("a"));
        now.addAndGet(15 * MIN);
        fail(t, "a", 5);
        assertEquals(30 * 60, t.retryAfterSeconds("a"));
        now.addAndGet(30 * MIN);
        fail(t, "a", 5);
        assertEquals(60 * 60, t.retryAfterSeconds("a"));
        for (int i = 0; i < 12; i++) { now.addAndGet(25 * 60 * MIN); fail(t, "a", 5); }
        assertEquals(24 * 60 * 60, t.retryAfterSeconds("a"));            // capped at 24 h
    }

    @Test void failuresWhileLockedDoNotExtendTheLock() {
        LoginThrottle t = throttle();
        fail(t, "a", 5);
        now.addAndGet(5 * MIN);
        fail(t, "a", 50);
        assertEquals(10 * 60, t.retryAfterSeconds("a"));
    }

    @Test void oldFailuresExpire() {
        LoginThrottle t = throttle();
        fail(t, "a", 4);
        now.addAndGet(16 * MIN);                                          // window (15 min) is over
        fail(t, "a", 4);
        assertEquals(0, t.retryAfterSeconds("a"));
    }

    @Test void successClearsCountersAndEscalation() {
        LoginThrottle t = throttle();
        fail(t, "a", 4);
        t.success("a");
        fail(t, "a", 4);
        assertEquals(0, t.retryAfterSeconds("a"));
        fail(t, "b", 5);
        now.addAndGet(15 * MIN);
        t.success("b");
        fail(t, "b", 5);
        assertEquals(15 * 60, t.retryAfterSeconds("b"));                  // escalation was reset
    }

    @Test void keysAreIndependent() {
        LoginThrottle t = throttle();
        fail(t, "a", 5);
        assertEquals(0, t.retryAfterSeconds("b"));
    }

    @Test void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new LoginThrottle(0, MIN, MIN, MIN, now::get));
    }

    // ---------------------------------------------------------------- LoginAttemptService
    @Test void lockIsPerUserAndAddress() {
        LoginAttemptService s = service();
        for (int i = 0; i < 5; i++) s.failure("Anna", "10.0.0.1");
        assertThrows(TooManyAttemptsException.class, () -> s.check("anna", "10.0.0.1"));      // user name is case-insensitive
        s.check("anna", "10.0.0.2");                                                          // same user, other address: allowed
        s.check("bob", "10.0.0.1");                                                           // other user, same address: allowed
    }

    @Test void exceptionCarriesRetryAfterAndMessage() {
        LoginAttemptService s = service();
        for (int i = 0; i < 5; i++) s.failure("anna", "10.0.0.1");
        TooManyAttemptsException e = assertThrows(TooManyAttemptsException.class, () -> s.check("anna", "10.0.0.1"));
        assertEquals(900, e.getRetryAfterSeconds());
        assertEquals(429, e.getStatusCode().value());
        assertEquals("900", e.getHeaders().getFirst("Retry-After"));
        assertTrue(e.getReason().contains("15 minutes"), e.getReason());
    }

    @Test void oneAddressTryingManyUsersGetsLocked() {
        LoginAttemptService s = service();
        for (int i = 0; i < 30; i++) s.failure("user" + i, "10.0.0.9");                       // 1 failure each: no pair lock
        assertThrows(TooManyAttemptsException.class, () -> s.check("someone-else", "10.0.0.9"));
        s.check("someone-else", "10.0.0.10");
    }

    @Test void distributedGuessingLocksTheUserShortlyAndDoesNotEscalate() {
        LoginAttemptService s = service();
        for (int i = 0; i < 30; i++) s.failure("victim", "192.168.0." + i);                  // 30 different addresses
        TooManyAttemptsException e = assertThrows(TooManyAttemptsException.class, () -> s.check("victim", "192.168.5.5"));
        assertEquals(900, e.getRetryAfterSeconds());
        now.addAndGet(15 * MIN);
        s.check("victim", "192.168.5.5");                                                     // lock is over
        for (int i = 0; i < 30; i++) s.failure("victim", "172.16.0." + i);
        assertEquals(900, assertThrows(TooManyAttemptsException.class, () -> s.check("victim", "1.1.1.1")).getRetryAfterSeconds());
    }

    @Test void successOnlyClearsTheUserAndAddressCounter() {
        LoginAttemptService s = service();
        for (int i = 0; i < 4; i++) s.failure("anna", "10.0.0.1");
        s.success("anna", "10.0.0.1");
        for (int i = 0; i < 4; i++) s.failure("anna", "10.0.0.1");
        s.check("anna", "10.0.0.1");                                                          // still below the limit
    }

    @Test void disabledServiceNeverBlocks() {
        LoginAttemptService s = new LoginAttemptService(false, 5, 15, 15, now::get);
        for (int i = 0; i < 500; i++) s.failure("anna", "10.0.0.1");
        s.check("anna", "10.0.0.1");
    }

    @Test void humanReadableTimes() {
        assertEquals("1 second", TooManyAttemptsException.human(1));
        assertEquals("45 seconds", TooManyAttemptsException.human(45));
        assertEquals("15 minutes", TooManyAttemptsException.human(900));
        assertEquals("3 hours", TooManyAttemptsException.human(3 * 3600));
    }
}
