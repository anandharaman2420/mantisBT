package com.mantis.api.soap;

import com.mantis.domain.User;
import com.mantis.repo.UserRepository;
import com.mantis.security.ClientIp;
import com.mantis.security.LoginAttemptService;
import com.mantis.security.TooManyAttemptsException;
import com.mantis.service.PasswordService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MantisConnect passes {@code username}/{@code password} in every request body (there is no session).
 * Authentication therefore happens per call and reuses the same password rules as the web UI.
 */
@Service
public class SoapAuthService {
    private final UserRepository users;
    private final PasswordService passwords;
    private final LoginAttemptService attempts;

    public SoapAuthService(UserRepository users, PasswordService passwords, LoginAttemptService attempts) {
        this.users = users;
        this.passwords = passwords;
        this.attempts = attempts;
    }

    @Transactional
    public User authenticate(String username, String password) {
        if (username == null || username.isBlank() || password == null)
            throw new SoapFault("Client", "Access denied: username and password are required");
        String name = username.trim();
        String ip = ClientIp.current();
        try {
            attempts.check(name, ip);
        } catch (TooManyAttemptsException e) {
            throw new SoapFault("Client", "Access denied: " + e.getReason());
        }
        User candidate = users.findByUsername(name).filter(User::isEnabled).orElse(null);
        boolean ok = candidate != null ? passwords.verify(candidate, password) : passwords.burn(password);
        if (!ok) {
            attempts.failure(name, ip);
            throw new SoapFault("Client", "Access denied");
        }
        attempts.success(name, ip);
        return candidate;
    }
}
