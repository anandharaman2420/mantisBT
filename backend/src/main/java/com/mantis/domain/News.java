package com.mantis.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** mantis_news_table */
@Entity @Table(name = "mantis_news_table")
@Getter @Setter @NoArgsConstructor
public class News {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "project_id") private Project project;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "poster_id") private User poster;
    @Column(name = "date_posted") private Instant datePosted = Instant.now();
    @Column(name = "last_modified") private Instant lastModified = Instant.now();
    @Column(name = "view_state") private int viewState = 10;
    private boolean announcement = false;
    @Column(nullable = false, length = 64) private String headline;
    @Column(nullable = false, columnDefinition = "text") private String body;
}
