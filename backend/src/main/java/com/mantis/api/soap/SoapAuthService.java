package com.mantis.api.soap;

import com.mantis.domain.User;
import com.mantis.repo.UserRepository;
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

    public SoapAuthService(UserRepository users, PasswordService passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    @Transactional
    public User authenticate(String username, String password) {
        if (username == null || username.isBlank() || password == null)
            throw new SoapFault("Client", "Access denied: username and password are required");
        User u = users.findByUsername(username.trim())
                .filter(User::isEnabled)
                .orElseThrow(() -> new SoapFault("Client", "Access denied"));
        if (!passwords.verify(u, password)) throw new SoapFault("Client", "Access denied");
        return u;
    }
}
