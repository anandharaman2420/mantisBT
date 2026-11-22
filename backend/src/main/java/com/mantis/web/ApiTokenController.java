package com.mantis.web;

import com.mantis.domain.ApiToken;
import com.mantis.domain.User;
import com.mantis.dto.Dtos.*;
import com.mantis.security.CurrentUser;
import com.mantis.service.AccessService;
import com.mantis.service.ApiTokenService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * API token management for external tools. Tokens are shown once at creation; the list never exposes the secret.
 * The normal JWT web session keeps working unchanged.
 */
@RestController
@RequestMapping("/api/tokens")
@Transactional
public class ApiTokenController {
    private final ApiTokenService tokens;
    private final CurrentUser current;
    private final AccessService access;

    public ApiTokenController(ApiTokenService tokens, CurrentUser current, AccessService access) {
        this.tokens = tokens; this.current = current; this.access = access;
    }

    @GetMapping
    public List<ApiTokenView> list() {
        User me = current.get();
        return tokens.list(me).stream().map(this::view).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiTokenCreated create(@Valid @RequestBody(required = false) ApiTokenForm form) {
        User me = current.get();
        ApiTokenService.Created created = tokens.create(me, form == null ? null : form.name());
        return new ApiTokenCreated(created.token(), view(created.entity()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable Long id) {
        User me = current.get();
        if (id == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token id is required");
        tokens.revoke(me, id, access.isAdmin(me));
    }

    private ApiTokenView view(ApiToken t) {
        return new ApiTokenView(t.getId(), t.getName(), t.getDateCreated(), t.getDateLastUsed(), t.isEnabled());
    }
}
