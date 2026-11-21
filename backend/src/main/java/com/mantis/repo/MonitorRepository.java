package com.mantis.repo;

import com.mantis.domain.IssueMonitor;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface MonitorRepository extends JpaRepository<IssueMonitor, Long> {
    List<IssueMonitor> findByIssueId(Long issueId);
    Optional<IssueMonitor> findByIssueIdAndUserId(Long issueId, Long userId);
    void deleteByIssueId(Long issueId);
}
