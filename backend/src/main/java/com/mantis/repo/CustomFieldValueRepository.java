package com.mantis.repo;

import com.mantis.domain.CustomFieldValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface CustomFieldValueRepository extends JpaRepository<CustomFieldValue, Long> {
    List<CustomFieldValue> findByIssueId(Long issueId);
    void deleteByIssueId(Long issueId);
    void deleteByFieldId(Long fieldId);

    /** values of a field on all issues of one project (used when a field is unlinked from the project) */
    @Modifying
    @Query("delete from CustomFieldValue v where v.field.id = :fid and v.issue.id in " +
           "(select i.id from Issue i where i.project.id = :pid)")
    void deleteByFieldAndProject(@Param("fid") Long fieldId, @Param("pid") Long projectId);
}
