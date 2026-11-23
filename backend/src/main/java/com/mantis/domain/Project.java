package com.mantis.domain;

import jakarta.persistence.*;
import lombok.*;

/** mantis_project_table */
@Entity @Table(name = "mantis_project_table")
@Getter @Setter @NoArgsConstructor
public class Project {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 128) private String name;
    /** 10 development, 30 release, 50 stable, 70 obsolete */
    private int status = 10;
    private boolean enabled = true;
    /** 10 public, 50 private */
    @Column(name = "view_state") private int viewState = 10;
    @Column(columnDefinition = "text") private String description = "";
    /** sub-project support: NULL = top level project */
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "parent_id") private Project parent;
    /** inherit categories, versions, custom fields and access levels from the parent (DDL default keeps old rows valid) */
    @Column(name = "inherit_parent", columnDefinition = "boolean default true") private boolean inheritParent = true;
}
