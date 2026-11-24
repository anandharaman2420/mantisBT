package com.mantis.security;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** HTTP 429 with a Retry-After header; the message tells the user how long to wait. */
public class TooManyAttemptsException extends ResponseStatusException {
    private final long retryAfterSeconds;

    public TooManyAttemptsException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "Too many failed login attempts. Try again in " + human(retryAfterSeconds) + ".");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        return h;
    }

    static String human(long seconds) {
        if (seconds < 90) return seconds + " second" + (seconds == 1 ? "" : "s");
        long minutes = (seconds + 59) / 60;
        if (minutes < 120) return minutes + " minutes";
        long hours = (minutes + 59) / 60;
        return hours + " hours";
    }
}
