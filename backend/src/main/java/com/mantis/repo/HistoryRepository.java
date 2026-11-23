package com.mantis.repo;

import com.mantis.domain.IssueHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface HistoryRepository extends JpaRepository<IssueHistory, Long> {
    List<IssueHistory> findByIssueIdOrderByDateModifiedAscIdAsc(Long issueId);
    void deleteByIssueId(Long issueId);
}
