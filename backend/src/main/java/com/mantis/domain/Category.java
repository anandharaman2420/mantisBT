package com.mantis.domain;

import jakarta.persistence.*;
import lombok.*;

/** mantis_category_table */
@Entity @Table(name = "mantis_category_table")
@Getter @Setter @NoArgsConstructor
public class Category {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "project_id") private Project project;
    @Column(nullable = false, length = 128) private String name;
    /** default assignee for issues in this category (Mantis user_id) */
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id") private User defaultHandler;
}
