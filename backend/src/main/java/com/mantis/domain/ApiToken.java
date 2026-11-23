package com.mantis.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * Long-lived API token used by external tools (Mantis REST / SOAP clients).
 *
 * <p>Only the SHA-256 hash of the token is stored; the raw token is shown exactly once at creation.
 * This keeps tokens safe if the database is read by someone who should not have the secret.</p>
 */
@Entity
@Table(name = "mantis_user_api_token_table", indexes = {
        @Index(name = "idx_api_token_user", columnList = "user_id")},
        uniqueConstraints = @UniqueConstraint(columnNames = {"token_hash"}))
@Getter @Setter @NoArgsConstructor
public class ApiToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "user_id") private User user;
    @Column(nullable = false, length = 100) private String name = "";
    @Column(name = "token_hash", nullable = false, length = 64) private String tokenHash;
    @Column(name = "date_created") private Instant dateCreated = Instant.now();
    @Column(name = "date_last_used") private Instant dateLastUsed;
    private boolean enabled = true;
}
