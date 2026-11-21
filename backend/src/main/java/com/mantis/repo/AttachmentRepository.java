package com.mantis.repo;

import com.mantis.domain.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    /** metadata only - the bytea column is NOT loaded */
    interface Meta {
        Long getId();
        String getTitle();
        String getFilename();
        Long getFilesize();
        String getFileType();
        Instant getDateAdded();
        Long getUserId();
    }

    @Query("select a.id as id, a.title as title, a.filename as filename, a.filesize as filesize, " +
           "a.fileType as fileType, a.dateAdded as dateAdded, a.user.id as userId " +
           "from Attachment a where a.issue.id = :issueId order by a.id")
    List<Meta> findMeta(@Param("issueId") Long issueId);

    void deleteByIssueId(Long issueId);
}
