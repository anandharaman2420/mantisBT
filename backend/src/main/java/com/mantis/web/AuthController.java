package com.mantis.web;

import com.mantis.domain.User;
import com.mantis.dto.Dtos.*;
import com.mantis.repo.UserRepository;
import com.mantis.security.CurrentUser;
import com.mantis.security.JwtService;
import com.mantis.service.Mapper;
import com.mantis.service.PasswordService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Map;

/** login.php / account_update.php */
@RestController
@RequestMapping("/api/auth")
@Transactional
public class AuthController {
    private final UserRepository users;
    private final PasswordService passwords;
    private final JwtService jwt;
    private final CurrentUser current;

    public AuthController(UserRepository users, PasswordService passwords, JwtService jwt, CurrentUser current) {
        this.users = users; this.passwords = passwords; this.jwt = jwt; this.current = current;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest r) {
        User u = users.findByUsername(r.username().trim())
                .filter(User::isEnabled)
                .filter(x -> passwords.verify(x, r.password()))
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
        if (!passwords.verify(u, r.currentPassword()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is wrong");
        u.setPassword(passwords.encode(r.newPassword()));
        return Map.of("message", "Password changed");
    }
}
