package com.mantis.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/** mantis_bug_file_table - file bytes are stored in the database (Mantis "DATABASE" upload method) */
@Entity @Table(name = "mantis_bug_file_table")
@Getter @Setter @NoArgsConstructor
public class Attachment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "bug_id") private Issue issue;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id") private User user;
    @Column(length = 250) private String title = "";
    @Column(columnDefinition = "text") private String description = "";
    @Column(nullable = false, length = 250) private String filename;
    private long filesize;
    @Column(name = "file_type", length = 250) private String fileType;
    @Column(name = "date_added") private Instant dateAdded = Instant.now();
    @JdbcTypeCode(SqlTypes.VARBINARY) @Column(columnDefinition = "bytea") private byte[] content;
}
