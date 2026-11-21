package com.mantis.service;

import com.mantis.domain.*;
import com.mantis.repo.HistoryRepository;
import org.springframework.stereotype.Service;

/** Port of Mantis history_api.php: every change to an issue is logged. */
@Service
public class HistoryService {
    private final HistoryRepository repo;

    public HistoryService(HistoryRepository repo) { this.repo = repo; }

    public void log(User by, Issue issue, String field, Object oldV, Object newV, int type) {
        IssueHistory h = new IssueHistory();
        h.setIssue(issue);
        h.setUser(by);
        h.setFieldName(field);
        h.setOldValue(oldV == null ? null : String.valueOf(oldV));
        h.setNewValue(newV == null ? null : String.valueOf(newV));
        h.setType(type);
        repo.save(h);
    }

    public void log(User by, Issue issue, String field, Object oldV, Object newV) {
        log(by, issue, field, oldV, newV, Lookups.H_NORMAL);
    }
}
