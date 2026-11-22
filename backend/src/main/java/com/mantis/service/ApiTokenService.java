package com.mantis.service;

import com.mantis.domain.ApiToken;
import com.mantis.domain.User;
import com.mantis.repo.ApiTokenRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Creates, validates and revokes long-lived API tokens (Mantis REST / SOAP clients). */
@Service
@Transactional
public class ApiTokenService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiTokenRepository tokens;

    public ApiTokenService(ApiTokenRepository tokens) { this.tokens = tokens; }

    /** raw token + the persisted entity (the raw value is never stored and is shown only once). */
    public record Created(String token, ApiToken entity) {}

    public Created create(User user, String name) {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String raw = HexFormat.of().formatHex(bytes);          // 48 hex chars
        ApiToken t = new ApiToken();
        t.setUser(user);
        t.setName(name == null || name.isBlank() ? "API token" : name.trim());
        t.setTokenHash(hash(raw));
        t.setEnabled(true);
        tokens.save(t);
        return new Created(raw, t);
    }

    /** @return the owner if the token exists, is enabled and the user is enabled; also records last use. */
    public Optional<User> validate(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        return tokens.findByTokenHash(hash(raw.trim()))
                .filter(ApiToken::isEnabled)
                .filter(t -> t.getUser().isEnabled())
                .map(t -> {
                    // Avoid a database write on every single request: one update per minute is enough.
                    Instant now = Instant.now();
                    if (t.getDateLastUsed() == null || t.getDateLastUsed().isBefore(now.minusSeconds(60))) {
                        t.setDateLastUsed(now);
                    }
                    return t.getUser();
                });
    }

    @Transactional(readOnly = true)
    public List<ApiToken> list(User user) {
        return tokens.findByUserIdOrderByDateCreatedDesc(user.getId());
    }

    public void revoke(User user, Long tokenId, boolean admin) {
        ApiToken t = tokens.findById(tokenId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API token not found"));
        if (!admin && !t.getUser().getId().equals(user.getId()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "API token not found");
        tokens.delete(t);
    }

    public void deleteAllForUser(Long userId) { tokens.deleteByUserId(userId); }

    public static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
