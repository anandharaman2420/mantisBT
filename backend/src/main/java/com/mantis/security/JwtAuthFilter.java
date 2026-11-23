package com.mantis.security;

import com.mantis.domain.User;
import com.mantis.repo.UserRepository;
import com.mantis.service.ApiTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Built manually in SecurityConfig (NOT a @Component, otherwise Boot also registers it as a servlet filter).
 *
 * <p>Authenticates two kinds of credentials:</p>
 * <ul>
 *   <li>{@code Authorization: Bearer <jwt>} — the normal web UI session token.</li>
 *   <li>{@code Authorization: <api-token>} (or {@code Bearer <api-token>}) — long-lived tokens used by
 *       external Mantis REST/SOAP clients. Only enabled when {@code app.api.token.enabled=true}.</li>
 * </ul>
 */
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final UserRepository users;
    /** null when API token authentication is disabled. */
    private final ApiTokenService apiTokens;

    public JwtAuthFilter(JwtService jwt, UserRepository users, ApiTokenService apiTokens) {
        this.jwt = jwt;
        this.users = users;
        this.apiTokens = apiTokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String header = req.getHeader("Authorization");
            if (header != null && !header.isBlank()) {
                if (header.startsWith("Bearer ")) {
                    String token = header.substring(7).trim();
                    if (!authenticateJwt(token) && apiTokens != null) authenticateApiToken(token);
                } else if (apiTokens != null) {
                    authenticateApiToken(header.trim());
                }
            }
        }
        chain.doFilter(req, res);
    }

    private boolean authenticateJwt(String token) {
        Optional<User> user = jwt.validate(token).flatMap(users::findByUsername).filter(User::isEnabled);
        user.ifPresent(this::setAuthentication);
        return user.isPresent();
    }

    private void authenticateApiToken(String token) {
        apiTokens.validate(token).ifPresent(this::setAuthentication);
    }

    private void setAuthentication(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getUsername(), null, List.of()));
    }
}
