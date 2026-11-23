package com.mantis.repo;

import com.mantis.domain.ProjectUser;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ProjectUserRepository extends JpaRepository<ProjectUser, Long> {
    Optional<ProjectUser> findByProjectIdAndUserId(Long projectId, Long userId);
    List<ProjectUser> findByProjectId(Long projectId);
    List<ProjectUser> findByUserId(Long userId);
    void deleteByProjectId(Long projectId);
}
