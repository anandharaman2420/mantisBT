package com.mantis.repo;

import com.mantis.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    List<Category> findByProjectIdOrderByName(Long projectId);
    boolean existsByProjectIdAndName(Long projectId, String name);
    void deleteByProjectId(Long projectId);
}
