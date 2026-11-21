package com.mantis.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** mantis_tag_table */
@Entity @Table(name = "mantis_tag_table")
@Getter @Setter @NoArgsConstructor
public class Tag {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 100) private String name;
    @Column(columnDefinition = "text") private String description = "";
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id") private User creator;
    @Column(name = "date_created") private Instant dateCreated = Instant.now();
}
