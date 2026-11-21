package com.mantis.web;

import com.mantis.domain.User;
import com.mantis.dto.Dtos.*;
import com.mantis.repo.UserRepository;
import com.mantis.security.CurrentUser;
import com.mantis.security.JwtService;
import com.mantis.service.Mapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/** login.php / account_update.php */
@RestController
@RequestMapping("/api/auth")
@Transactional
public class AuthController {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final CurrentUser current;

    public AuthController(UserRepository users, PasswordEncoder encoder, JwtService jwt, CurrentUser current) {
        this.users = users; this.encoder = encoder; this.jwt = jwt; this.current = current;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest r) {
        User u = users.findByUsername(r.username().trim())
                .filter(User::isEnabled)
                .filter(x -> passwordMatches(x, r.password()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password"));
        u.setLastVisit(Instant.now());
        u.setLoginCount(u.getLoginCount() + 1);
        return new LoginResponse(jwt.generate(u.getUsername()), Mapper.user(u));
    }

    @GetMapping("/me")
    public UserView me() { return Mapper.user(current.get()); }

    @PutMapping("/preferences")
    public UserView preferences(@RequestBody Preferences p) {
        User u = current.get();
        if (p.emailEnabled() != null) u.setEmailEnabled(p.emailEnabled());
        return Mapper.user(u);
    }

    @PostMapping("/change-password")
    public Map<String, String> changePassword(@Valid @RequestBody ChangePassword r) {
        User u = current.get();
        if (!passwordMatches(u, r.currentPassword()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is wrong");
        u.setPassword(encoder.encode(r.newPassword()));
        return Map.of("message", "Password changed");
    }

    /**
     * Accepts BCrypt hashes (new) and legacy MantisBT MD5 hashes (imported users);
     * a legacy hash is transparently upgraded to BCrypt on the first successful login.
     */
    private boolean passwordMatches(User u, String raw) {
        String stored = u.getPassword();
        if (stored.startsWith("$2")) return encoder.matches(raw, stored);
        if (stored.matches("[0-9a-fA-F]{32}")) {
            try {
                String md5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(raw.getBytes(StandardCharsets.UTF_8)));
                if (md5.equalsIgnoreCase(stored)) { u.setPassword(encoder.encode(raw)); return true; }
            } catch (Exception ignored) {}
        }
        return false;
    }
}
