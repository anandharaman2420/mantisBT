package com.mantis.domain;

import jakarta.persistence.*;
import lombok.*;

/** mantis_project_user_list_table : per-project access level override */
@Entity @Table(name = "mantis_project_user_list_table",
        uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "user_id"}))
@Getter @Setter @NoArgsConstructor
public class ProjectUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "project_id") private Project project;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "user_id") private User user;
    @Column(name = "access_level") private int accessLevel = 25;
}
