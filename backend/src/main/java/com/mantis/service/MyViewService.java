package com.mantis.service;

import com.mantis.domain.*;
import com.mantis.dto.Dtos.*;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * "My View" (Mantis my_view_page.php): boxes with the 10 newest issues and the total for what matters to the user.
 * Everything goes through the normal search, so private issues / projects are respected.
 */
@Service
@Transactional(readOnly = true)
public class MyViewService {
    private static final int LIMIT = 10;

    private final IssueService issues;
    private final AccessService access;

    public MyViewService(IssueService issues, AccessService access) { this.issues = issues; this.access = access; }

    public MyView view(User me, Long projectId) {
        Specification<Issue> unresolved = (r, q, cb) -> cb.lessThan(r.<Integer>get("status"), Lookups.RESOLVED);
        Specification<Issue> awaiting = (r, q, cb) -> cb.equal(r.<Integer>get("status"), Lookups.RESOLVED);
        Specification<Issue> unassigned = (r, q, cb) -> cb.and(cb.isNull(r.get("handler")),
                cb.lessThan(r.<Integer>get("status"), Lookups.RESOLVED));
        Specification<Issue> monitored = (r, q, cb) -> {
            Subquery<Long> sq = q.subquery(Long.class);
            Root<IssueMonitor> m = sq.from(IssueMonitor.class);
            sq.select(m.<Long>get("id")).where(cb.equal(m.get("issue"), r), cb.equal(m.get("user").get("id"), me.getId()));
            return cb.exists(sq);
        };
        Instant since = Instant.now().minus(7, ChronoUnit.DAYS);
        Specification<Issue> recent = (r, q, cb) -> cb.greaterThanOrEqualTo(r.<Instant>get("lastUpdated"), since);

        List<MyViewBox> boxes = new ArrayList<>();
        boxes.add(box(me, "assigned", "Assigned to me (unresolved)", filter(projectId, me.getId(), null), unresolved));
        boxes.add(box(me, "reported", "Reported by me (unresolved)", filter(projectId, null, me.getId()), unresolved));
        boxes.add(box(me, "verify", "Resolved - waiting for my verification", filter(projectId, null, me.getId()), awaiting));
        boxes.add(box(me, "monitored", "Monitored by me (unresolved)", filter(projectId, null, null), monitored.and(unresolved)));
        if (access.levels(me).values().stream().anyMatch(l -> l >= Lookups.DEVELOPER))          // only people who can assign
            boxes.add(box(me, "unassigned", "Unassigned", filter(projectId, null, null), unassigned));
        boxes.add(box(me, "recent", "Modified in the last 7 days", filter(projectId, null, null), recent));
        return new MyView(boxes);
    }

    private static IssueService.Filter filter(Long projectId, Long handlerId, Long reporterId) {
        return new IssueService.Filter(projectId, null, handlerId, reporterId, null, null, null, null, false, null);
    }

    private MyViewBox box(User me, String key, String title, IssueService.Filter f, Specification<Issue> extra) {
        Page<Issue> pg = issues.search(me, f, extra, PageRequest.of(0, LIMIT,
                Sort.by(Sort.Direction.DESC, "lastUpdated").and(Sort.by(Sort.Direction.DESC, "id"))));
        return new MyViewBox(key, title, pg.getTotalElements(), pg.getContent().stream().map(Mapper::summary).toList());
    }
}
