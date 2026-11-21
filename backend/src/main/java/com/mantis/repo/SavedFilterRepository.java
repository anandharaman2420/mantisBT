package com.mantis.repo;

import com.mantis.domain.SavedFilter;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;

public interface SavedFilterRepository extends JpaRepository<SavedFilter, Long> {
    @EntityGraph(attributePaths = {"user", "project"})
    @Query("select f from SavedFilter f where f.user.id = :userId or (f.isPublic = true and (f.project is null or f.project.id in :projectIds)) order by f.name asc")
    List<SavedFilter> findVisible(@Param("userId") Long userId, @Param("projectIds") Collection<Long> projectIds);

    @EntityGraph(attributePaths = {"user", "project"})
    List<SavedFilter> findByUserIdOrderByNameAsc(Long userId);

    void deleteByProjectId(Long projectId);
    void deleteByUserId(Long userId);
}
