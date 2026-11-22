package com.mantis.repo;

import com.mantis.domain.Issue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;

public interface IssueRepository extends JpaRepository<Issue, Long>, JpaSpecificationExecutor<Issue> {

    /** avoids N+1 selects when listing */
    @Override
    @EntityGraph(attributePaths = {"project", "reporter", "handler", "category"})
    Page<Issue> findAll(Specification<Issue> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"project", "reporter", "handler", "category"})
    List<Issue> findAll(Specification<Issue> spec, org.springframework.data.domain.Sort sort);

    @Query("select i.status, count(i) from Issue i where i.project.id in :ids group by i.status")
    List<Object[]> countByStatus(@Param("ids") Collection<Long> ids);

    @Query("select i.priority, count(i) from Issue i where i.project.id in :ids and i.status < 80 group by i.priority")
    List<Object[]> countOpenByPriority(@Param("ids") Collection<Long> ids);

    @Query("select i.severity, count(i) from Issue i where i.project.id in :ids and i.status < 80 group by i.severity")
    List<Object[]> countOpenBySeverity(@Param("ids") Collection<Long> ids);

    @Query("select count(i) from Issue i where i.project.id in :ids and i.status < 80 and i.handler is null")
    long countUnassigned(@Param("ids") Collection<Long> ids);

    long countByProjectId(Long projectId);
    long countByCategoryId(Long categoryId);
}
