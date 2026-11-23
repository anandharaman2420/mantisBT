package com.mantis.repo;

import com.mantis.domain.ProjectVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface VersionRepository extends JpaRepository<ProjectVersion, Long> {
    List<ProjectVersion> findByProjectIdOrderByDateOrderDesc(Long projectId);
    void deleteByProjectId(Long projectId);
}
