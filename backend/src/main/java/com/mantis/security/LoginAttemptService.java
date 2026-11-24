package com.mantis.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Brute-force protection for every place a password is checked (web login, change password, SOAP login).
 *
 *  - per user + address : max-failures wrong passwords in the window lock that combination (15 min, then 30, 60 ... up to 24 h)
 *  - per address        : 6x as many failures lock the address (one machine trying many user names)
 *  - per user           : 6x as many failures from anywhere lock the user for a short, non-growing time (distributed guessing);
 *                         kept short and non-escalating so that nobody can lock a real user out for long
 *
 * In-memory: counters reset on restart and are not shared between several application instances.
 * Behind a reverse proxy set server.forward-headers-strategy=native so that the real client address is used.
 */
@Service
public class LoginAttemptService {
    private static final long DAY = 24L * 60 * 60 * 1000;

    private final boolean enabled;
    private final LoginThrottle perUserAndIp;
    private final LoginThrottle perIp;
    private final LoginThrottle perUser;

    @Autowired
    public LoginAttemptService(@Value("${app.security.login-throttle.enabled:true}") boolean enabled,
                               @Value("${app.security.login-throttle.max-failures:5}") int maxFailures,
                               @Value("${app.security.login-throttle.window-minutes:15}") int windowMinutes,
                               @Value("${app.security.login-throttle.lock-minutes:15}") int lockMinutes) {
        this(enabled, maxFailures, windowMinutes, lockMinutes, System::currentTimeMillis);
    }

    LoginAttemptService(boolean enabled, int maxFailures, int windowMinutes, int lockMinutes, LongSupplier clock) {
        this.enabled = enabled;
        long window = windowMinutes * 60_000L, lock = lockMinutes * 60_000L;
        int many = Math.max(1, maxFailures) * 6;
        this.perUserAndIp = new LoginThrottle(maxFailures, window, lock, DAY, clock);
        this.perIp = new LoginThrottle(many, window, lock, DAY, clock);
        this.perUser = new LoginThrottle(many, window, lock, lock, clock);
    }

    /** @throws TooManyAttemptsException (HTTP 429) when this user / address is currently locked */
    public void check(String username, String ip) {
        if (!enabled) return;
        long wait = Math.max(perUserAndIp.retryAfterSeconds(pair(username, ip)),
                Math.max(perIp.retryAfterSeconds(addr(ip)), perUser.retryAfterSeconds(user(username))));
        if (wait > 0) throw new TooManyAttemptsException(wait);
    }

    public void failure(String username, String ip) {
        if (!enabled) return;
        perUserAndIp.failure(pair(username, ip));
        perIp.failure(addr(ip));
        perUser.failure(user(username));
    }

    /** only the user+address counter is cleared: a success must not wipe the evidence of a spraying address */
    public void success(String username, String ip) {
        if (!enabled) return;
        perUserAndIp.success(pair(username, ip));
    }

    private static String user(String u) {
        String k = u == null ? "" : u.trim().toLowerCase(Locale.ROOT);
        return k.length() > 100 ? k.substring(0, 100) : k;
    }
    private static String addr(String ip) { return ip == null || ip.isBlank() ? "unknown" : ip; }
    private static String pair(String u, String ip) { return user(u) + "|" + addr(ip); }
}
