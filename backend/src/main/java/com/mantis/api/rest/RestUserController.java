package com.mantis.api.rest;

import com.mantis.api.rest.RestDtos.UserDto;
import com.mantis.domain.User;
import com.mantis.repo.UserRepository;
import com.mantis.security.CurrentUser;
import com.mantis.service.AccessService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Mantis REST compatible user endpoints ({@code /api/rest/users}). */
@RestController
@RequestMapping("/api/rest")
@ConditionalOnProperty(name = "app.api.rest.enabled", havingValue = "true", matchIfMissing = true)
@Transactional(readOnly = true)
public class RestUserController {
    private final CurrentUser current;
    private final UserRepository users;
    private final AccessService access;

    public RestUserController(CurrentUser current, UserRepository users, AccessService access) {
        this.current = current; this.users = users; this.access = access;
    }

    @GetMapping("/users/me")
    public UserDto me() {
        return RestMapper.user(current.get());
    }

    @GetMapping("/users/{id}")
    public UserDto get(@PathVariable Long id) {
        User me = current.get();
        if (!me.getId().equals(id) && !access.isAdmin(me))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        User u = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        return RestMapper.user(u);
    }
}
