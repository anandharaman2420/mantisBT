package com.mantis.domain;

import jakarta.persistence.*;
import lombok.*;

/** mantis_bug_monitor_table */
@Entity @Table(name = "mantis_bug_monitor_table",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "bug_id"}))
@Getter @Setter @NoArgsConstructor
public class IssueMonitor {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "user_id") private User user;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "bug_id") private Issue issue;
}
