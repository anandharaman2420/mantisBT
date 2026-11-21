package com.mantis.domain;

import jakarta.persistence.*;
import lombok.*;

/** mantis_custom_field_string_table - the value of one custom field on one issue (multi values joined with '|') */
@Entity @Table(name = "mantis_custom_field_string_table",
        uniqueConstraints = @UniqueConstraint(columnNames = {"field_id", "bug_id"}))
@Getter @Setter @NoArgsConstructor
public class CustomFieldValue {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "field_id") private CustomField field;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "bug_id") private Issue issue;
    @Column(columnDefinition = "text") private String value = "";
}
