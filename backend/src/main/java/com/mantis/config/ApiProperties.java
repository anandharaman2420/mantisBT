package com.mantis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the MantisBT REST / SOAP compatibility layer.
 * Bound from the {@code app.api.*} keys in application.yml (all overridable with environment variables).
 */
@Component
@ConfigurationProperties(prefix = "app.api")
public class ApiProperties {

    private final Rest rest = new Rest();
    private final Soap soap = new Soap();
    private final Token token = new Token();
    private final Logging logging = new Logging();
    private final RateLimit rateLimit = new RateLimit();
    private final Attachment attachment = new Attachment();

    public Rest getRest() { return rest; }
    public Soap getSoap() { return soap; }
    public Token getToken() { return token; }
    public Logging getLogging() { return logging; }
    public RateLimit getRateLimit() { return rateLimit; }
    public Attachment getAttachment() { return attachment; }

    public static class Rest {
        /** expose the Mantis-compatible REST API under {@code /api/rest}. */
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Soap {
        /** expose the MantisConnect SOAP endpoint under {@code /api/soap}. */
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Token {
        /** accept long-lived API tokens in the Authorization header. */
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Logging {
        /** log method/path/status/duration for the compatibility endpoints. */
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class RateLimit {
        private boolean enabled = true;
        private int requestsPerMinute = 120;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getRequestsPerMinute() { return requestsPerMinute; }
        public void setRequestsPerMinute(int requestsPerMinute) { this.requestsPerMinute = requestsPerMinute; }
    }

    public static class Attachment {
        /** hard upload limit enforced by the compatibility API (bytes). */
        private long maxSizeBytes = 5L * 1024 * 1024;
        public long getMaxSizeBytes() { return maxSizeBytes; }
        public void setMaxSizeBytes(long maxSizeBytes) { this.maxSizeBytes = maxSizeBytes; }
    }
}
