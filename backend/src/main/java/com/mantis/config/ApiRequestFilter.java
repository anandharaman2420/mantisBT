package com.mantis.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.Principal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies to {@code /api/rest/**} and {@code /api/soap/**} only.
 *
 * <ul>
 *   <li><b>Rate limiting</b> – a per-instance sliding window (default 120 requests/minute per user or
 *       remote address). Exceeding it returns HTTP 429. This is deliberately simple: for a cluster,
 *       replace it with a shared store (Redis) or an API gateway.</li>
 *   <li><b>Request logging</b> – method, path, status and duration. Bodies and credentials are never
 *       logged.</li>
 * </ul>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiRequestFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(ApiRequestFilter.class);
    private static final long WINDOW_MS = 60_000L;

    private final ApiProperties props;
    private final ConcurrentHashMap<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    public ApiRequestFilter(ApiProperties props) { this.props = props; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/api/rest") || path.startsWith("/api/soap"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (props.getRateLimit().isEnabled() && !allow(key(req))) {
            res.setStatus(429);
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.setCharacterEncoding("UTF-8");
            res.getWriter().write("{\"code\":429,\"message\":\"Too many requests\"}");
            return;
        }

        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            if (props.getLogging().isEnabled()) {
                long ms = (System.nanoTime() - start) / 1_000_000L;
                log.info("API {} {} -> {} ({} ms)", req.getMethod(), req.getRequestURI(), res.getStatus(), ms);
            }
        }
    }

    private boolean allow(String key) {
        int limit = Math.max(props.getRateLimit().getRequestsPerMinute(), 1);
        long now = System.currentTimeMillis();
        if (windows.size() > 10_000) prune(now);        // bound memory on long-running instances
        Deque<Long> window = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MS) window.pollFirst();
            if (window.size() >= limit) return false;
            window.addLast(now);
            return true;
        }
    }

    /** Drops windows that have been idle for at least one full window. */
    private void prune(long now) {
        windows.entrySet().removeIf(e -> {
            Deque<Long> w = e.getValue();
            synchronized (w) {
                return w.isEmpty() || now - w.peekLast() >= WINDOW_MS;
            }
        });
    }

    /** Authenticated username when available, otherwise the client address. */
    private static String key(HttpServletRequest req) {
        Principal principal = req.getUserPrincipal();
        return principal != null && principal.getName() != null ? "user:" + principal.getName() : "ip:" + req.getRemoteAddr();
    }
}
