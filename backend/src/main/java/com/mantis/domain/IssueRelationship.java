package com.mantis.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * mantis_bug_relationship_table.
 * type (stored on the SOURCE issue, Mantis constants): 0 duplicate of, 1 related to,
 * 2 parent of (dependant), 3 child of (blocks), 4 has duplicate
 */
@Entity @Table(name = "mantis_bug_relationship_table")
@Getter @Setter @NoArgsConstructor
public class IssueRelationship {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "source_bug_id") private Issue source;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "destination_bug_id") private Issue destination;
    @Column(name = "relationship_type") private int relationshipType = 1;
}
