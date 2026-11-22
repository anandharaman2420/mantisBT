package com.mantis.domain;

import jakarta.persistence.*;
import lombok.*;

/** mantis_custom_field_project_table - which fields a project uses, and in which order */
@Entity @Table(name = "mantis_custom_field_project_table",
        uniqueConstraints = @UniqueConstraint(columnNames = {"field_id", "project_id"}))
@Getter @Setter @NoArgsConstructor
public class CustomFieldProject {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "field_id") private CustomField field;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "project_id") private Project project;
    private int sequence = 0;
}
