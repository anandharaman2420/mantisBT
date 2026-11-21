package com.mantis.security;

import com.mantis.repo.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

/** Built manually in SecurityConfig (NOT a @Component, otherwise Boot also registers it as a servlet filter). */
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final UserRepository users;

    public JwtAuthFilter(JwtService jwt, UserRepository users) {
        this.jwt = jwt;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ") && SecurityContextHolder.getContext().getAuthentication() == null) {
            jwt.validate(h.substring(7)).flatMap(users::findByUsername)
                    .filter(u -> u.isEnabled())
                    .ifPresent(u -> SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(u.getUsername(), null, List.of())));
        }
        chain.doFilter(req, res);
    }
}
