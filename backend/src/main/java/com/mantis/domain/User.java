package com.mantis.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** mantis_user_table */
@Entity @Table(name = "mantis_user_table")
@Getter @Setter @NoArgsConstructor
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 191) private String username;
    @Column(length = 191) private String realname = "";
    @Column(nullable = false, length = 191) private String email = "";
    @Column(nullable = false, length = 255) private String password;
    @Column(name = "date_created") private Instant dateCreated = Instant.now();
    @Column(name = "last_visit") private Instant lastVisit;
    private boolean enabled = true;
    @Column(name = "protected") private boolean protectedUser = false;
    @Column(name = "access_level") private int accessLevel = 25;
    @Column(name = "login_count") private int loginCount = 0;
    /** e-mail notifications on/off (default true also for rows that already exist) */
    @Column(name = "email_enabled", columnDefinition = "boolean default true") private boolean emailEnabled = true;
}
