package com.mantis.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** mantis_bug_history_table - audit trail of every change */
@Entity @Table(name = "mantis_bug_history_table", indexes = @Index(name = "idx_history_bug", columnList = "bug_id"))
@Getter @Setter @NoArgsConstructor
public class IssueHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "bug_id") private Issue issue;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id") private User user;
    @Column(name = "field_name", length = 64) private String fieldName;
    @Column(name = "old_value", columnDefinition = "text") private String oldValue;
    @Column(name = "new_value", columnDefinition = "text") private String newValue;
    /** Mantis history type: 0 normal, 1 new bug, 2 note added, 3 note updated, 9 file added, 10 file deleted,
     *  12 monitor, 13 unmonitor, 18 relationship added, 19 relationship deleted, 25 tag attached, 26 tag detached */
    private int type = 0;
    @Column(name = "date_modified") private Instant dateModified = Instant.now();
}
