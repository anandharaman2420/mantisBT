package com.mantis.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * mantis_bug_table + mantis_bug_text_table merged into one entity
 * (description / steps_to_reproduce / additional_information live here).
 * Mantis stored "0" for "no handler"; here handler is simply NULL.
 */
@Entity @Table(name = "mantis_bug_table", indexes = {
        @Index(name = "idx_bug_status", columnList = "status"),
        @Index(name = "idx_bug_project", columnList = "project_id"),
        @Index(name = "idx_bug_fixed_in_version", columnList = "fixed_in_version")})
@Getter @Setter @NoArgsConstructor
public class Issue {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "project_id") private Project project;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "reporter_id") private User reporter;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "handler_id") private User handler;
    @Column(name = "duplicate_id") private Long duplicateId;
    private int priority = 30;
    private int severity = 50;
    private int reproducibility = 10;
    private int status = 10;
    private int resolution = 10;
    private int projection = 10;
    private int eta = 10;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "category_id") private Category category;
    @Column(name = "date_submitted") private Instant dateSubmitted = Instant.now();
    @Column(name = "last_updated") private Instant lastUpdated = Instant.now();
    @Column(length = 32) private String os = "";
    @Column(name = "os_build", length = 32) private String osBuild = "";
    @Column(length = 32) private String platform = "";
    @Column(length = 64) private String version = "";
    @Column(name = "fixed_in_version", length = 64) private String fixedInVersion = "";
    @Column(name = "target_version", length = 64) private String targetVersion = "";
    @Column(length = 32) private String build = "";
    @Column(name = "view_state") private int viewState = 10;
    @Column(nullable = false, length = 128) private String summary;
    private boolean sticky = false;
    @Column(nullable = false, columnDefinition = "text") private String description = "";
    @Column(name = "steps_to_reproduce", columnDefinition = "text") private String stepsToReproduce = "";
    @Column(name = "additional_information", columnDefinition = "text") private String additionalInformation = "";

    /** mantis_bug_tag_table */
    @ManyToMany
    @JoinTable(name = "mantis_bug_tag_table",
            joinColumns = @JoinColumn(name = "bug_id"), inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<Tag> tags = new HashSet<>();
}
