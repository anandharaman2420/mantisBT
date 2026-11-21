package com.mantis.security;

import com.mantis.domain.User;
import com.mantis.repo.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Equivalent of Mantis current_user_api.php */
@Component
public class CurrentUser {
    private final UserRepository users;

    public CurrentUser(UserRepository users) { this.users = users; }

    public User get() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || a.getName() == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        return users.findByUsername(a.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user"));
    }
}
