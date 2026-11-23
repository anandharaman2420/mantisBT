package com.mantis.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** mantis_project_version_table */
@Entity @Table(name = "mantis_project_version_table")
@Getter @Setter @NoArgsConstructor
public class ProjectVersion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "project_id") private Project project;
    @Column(nullable = false, length = 64) private String version;
    @Column(columnDefinition = "text") private String description = "";
    private boolean released = false;
    private boolean obsolete = false;
    @Column(name = "date_order") private Instant dateOrder = Instant.now();
}
