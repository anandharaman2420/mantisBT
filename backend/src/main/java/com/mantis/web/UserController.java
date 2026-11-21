package com.mantis.web;

import com.mantis.domain.Lookups;
import com.mantis.domain.Project;
import com.mantis.domain.User;
import com.mantis.dto.Dtos.*;
import com.mantis.repo.UserRepository;
import com.mantis.security.CurrentUser;
import com.mantis.service.AccessService;
import com.mantis.service.Mapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

/** manage_user_*.php (administrator) + user lookups for the assign dropdown */
@RestController
@RequestMapping("/api/users")
@Transactional
public class UserController {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final CurrentUser current;
    private final AccessService access;

    public UserController(UserRepository users, PasswordEncoder encoder, CurrentUser current, AccessService access) {
        this.users = users; this.encoder = encoder; this.current = current; this.access = access;
    }

    @GetMapping
    public List<UserView> list() {
        access.requireAdmin(current.get());
        return users.findAllByOrderByUsername().stream().map(Mapper::user).toList();
    }

    /** users who may be assigned issues (developer+) in a project */
    @GetMapping("/assignable")
    public List<UserRef> assignable(@RequestParam Long projectId) {
        User me = current.get();
        Project p = access.projectOrThrow(projectId);
        access.require(me, p, Lookups.VIEWER);
        return users.findAllByOrderByUsername().stream()
                .filter(u -> u.isEnabled() && access.levelIn(u, p) >= Lookups.DEVELOPER)
                .map(Mapper::ref).toList();
    }

    /** minimal user list for pickers (reporter filter, project members) - developer+ only */
    @GetMapping("/lookup")
    public List<UserRef> lookup() {
        User me = current.get();
        if (me.getAccessLevel() < Lookups.DEVELOPER) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        return users.findAllByOrderByUsername().stream().filter(User::isEnabled).map(Mapper::ref).toList();
    }

    @PostMapping
    public UserView create(@Valid @RequestBody UserForm f) {
        access.requireAdmin(current.get());
        if (f.password() == null || f.password().length() < 6) throw bad("Password must be at least 6 characters");
        if (users.existsByUsername(f.username().trim())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        User u = new User();
        u.setUsername(f.username().trim());
        apply(u, f);
        u.setPassword(encoder.encode(f.password()));
        return Mapper.user(users.save(u));
    }

    @PutMapping("/{id}")
    public UserView update(@PathVariable Long id, @Valid @RequestBody UserForm f) {
        User me = current.get();
        access.requireAdmin(me);
        User u = users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (!u.getUsername().equals(f.username().trim())) {
            if (users.existsByUsername(f.username().trim())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
            u.setUsername(f.username().trim());
        }
        if (u.getId().equals(me.getId()) && (Boolean.FALSE.equals(f.enabled())
                || (f.accessLevel() != null && f.accessLevel() < Lookups.ADMINISTRATOR)))
            throw bad("You cannot disable or demote your own account");
        apply(u, f);
        if (f.password() != null && !f.password().isBlank()) {
            if (f.password().length() < 6) throw bad("Password must be at least 6 characters");
            u.setPassword(encoder.encode(f.password()));
        }
        return Mapper.user(u);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        User me = current.get();
        access.requireAdmin(me);
        if (me.getId().equals(id)) throw bad("You cannot delete your own account");
        User u = users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        users.delete(u);
        users.flush();     // surface FK violations here (user still referenced by issues) -> 409
    }

    private void apply(User u, UserForm f) {
        if (f.realname() != null) u.setRealname(f.realname());
        if (f.email() != null) u.setEmail(f.email());
        if (f.accessLevel() != null) {
            if (!Lookups.ACCESS.containsKey(f.accessLevel()) || f.accessLevel() == 0) throw bad("Invalid access level");
            u.setAccessLevel(f.accessLevel());
        }
        if (f.enabled() != null) u.setEnabled(f.enabled());
    }

    private ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }
}
