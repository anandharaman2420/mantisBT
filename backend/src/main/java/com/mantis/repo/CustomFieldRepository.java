package com.mantis.repo;

import com.mantis.domain.CustomField;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CustomFieldRepository extends JpaRepository<CustomField, Long> {
    List<CustomField> findAllByOrderByName();
    boolean existsByNameIgnoreCase(String name);
}
