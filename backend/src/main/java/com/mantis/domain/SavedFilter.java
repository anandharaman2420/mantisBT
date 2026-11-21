package com.mantis.domain;

import jakarta.persistence.*;
import lombok.*;

/** mantis_filters_table - saved filter criteria for issues */
@Entity
@Table(name = "mantis_filters_table")
@Getter @Setter @NoArgsConstructor
public class SavedFilter {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    /** JSON representation of filter criteria */
    @Column(name = "filter_string", columnDefinition = "text", nullable = false)
    private String filterString = "{}";
}
