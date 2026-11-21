package com.mantis.service;

import com.mantis.domain.*;
import com.mantis.repo.HistoryRepository;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

/** Port of Mantis history_api.php: every change to an issue is logged. */
@Service
public class HistoryService {
    private final HistoryRepository repo;
    /** (field, old, new) changes of the request currently being processed (used for e-mail notifications) */
    private final ThreadLocal<List<String[]>> changes = new ThreadLocal<>();

    public void begin() { changes.set(new ArrayList<>()); }
    public List<String[]> changes() { List<String[]> l = changes.get(); return l == null ? List.of() : new ArrayList<>(l); }
    public void end() { changes.remove(); }

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
        List<String[]> l = changes.get();
        if (l != null && type == Lookups.H_NORMAL && field != null && !field.isEmpty())
            l.add(new String[]{field, h.getOldValue(), h.getNewValue()});
    }

    public void log(User by, Issue issue, String field, Object oldV, Object newV) {
        log(by, issue, field, oldV, newV, Lookups.H_NORMAL);
    }
}
