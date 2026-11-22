package com.mantis.repo;

import com.mantis.domain.CustomFieldProject;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CustomFieldProjectRepository extends JpaRepository<CustomFieldProject, Long> {
    List<CustomFieldProject> findByProjectIdOrderBySequenceAscIdAsc(Long projectId);
    List<CustomFieldProject> findByFieldId(Long fieldId);
    Optional<CustomFieldProject> findByFieldIdAndProjectId(Long fieldId, Long projectId);
    void deleteByProjectId(Long projectId);
    void deleteByFieldId(Long fieldId);
}
