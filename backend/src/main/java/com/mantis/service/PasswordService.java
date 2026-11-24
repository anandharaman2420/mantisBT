package com.mantis.service;

import com.mantis.domain.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifies passwords against the stored hash.
 * Accepts BCrypt (new hashes) and legacy MantisBT MD5 hashes (imported users). A legacy hash that matches is
 * transparently upgraded to BCrypt in the entity; the caller must save the user (it is managed in a transaction).
 */
@Service
public class PasswordService {
    private final PasswordEncoder encoder;
    /** hash of a random value, only used by burn() */
    private final String dummyHash;

    public PasswordService(PasswordEncoder encoder) {
        this.encoder = encoder;
        this.dummyHash = encoder.encode(java.util.UUID.randomUUID().toString());
    }

    /**
     * Spends the same time as a real password check. Called when the user name does not exist (or is disabled), so that
     * "unknown user" does not answer measurably faster than "wrong password" (user enumeration by timing).
     * @return always false
     */
    public boolean burn(String raw) {
        encoder.matches(raw == null ? "" : raw, dummyHash);
        return false;
    }

    public String encode(String raw) { return encoder.encode(raw); }

    public boolean verify(User user, String raw) {
        if (user == null || raw == null) return false;
        String stored = user.getPassword();
        if (stored == null || stored.isEmpty()) return false;
        if (stored.startsWith("$2")) return encoder.matches(raw, stored);
        if (stored.matches("[0-9a-fA-F]{32}")) {
            try {
                String md5 = HexFormat.of().formatHex(
                        MessageDigest.getInstance("MD5").digest(raw.getBytes(StandardCharsets.UTF_8)));
                if (md5.equalsIgnoreCase(stored)) {
                    user.setPassword(encoder.encode(raw));
                    return true;
                }
            } catch (Exception ignored) {
                // fall through: invalid legacy hash
            }
        }
        return false;
    }
}
