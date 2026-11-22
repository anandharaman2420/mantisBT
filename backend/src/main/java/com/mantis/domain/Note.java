package com.mantis.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** mantis_bugnote_table + mantis_bugnote_text_table merged */
@Entity @Table(name = "mantis_bugnote_table", indexes = @Index(name = "idx_bugnote_bug", columnList = "bug_id"))
@Getter @Setter @NoArgsConstructor
public class Note {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "bug_id") private Issue issue;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "reporter_id") private User reporter;
    @Column(nullable = false, columnDefinition = "text") private String text;
    @Column(name = "view_state") private int viewState = 10;
    @Column(name = "date_submitted") private Instant dateSubmitted = Instant.now();
    @Column(name = "last_modified") private Instant lastModified = Instant.now();
    /** 0 normal, 1 reminder, 2 time tracking */
    @Column(name = "note_type") private int noteType = 0;
    /** minutes spent (Mantis time tracking); column has a DDL default so existing rows stay valid */
    @Column(name = "time_tracking", columnDefinition = "integer default 0") private int timeTracking = 0;
}
