package com.mantis.service;

import com.mantis.domain.*;
import com.mantis.service.SummaryReport.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/**
 * The Summary page (Mantis summary_page.php). Everything is aggregated IN THE DATABASE (GROUP BY / COUNT), so it also works for
 * hundreds of thousands of issues, and every query carries the visibility rules of the normal search: a private issue or a private
 * project is never counted for somebody who may not see it. The pure rules (date windows, age buckets) live in {@link SummaryReport}.
 */
@Service
@Transactional(readOnly = true)
public class SummaryService {
    private static final int TOP = 10;
    private static final int MAX_GROUPS = 50;

    @PersistenceContext
    private EntityManager em;

    private final IssueService issues;

    public SummaryService(IssueService issues) { this.issues = issues; }

    public Report report(User me, Long projectId) {
        Specification<Issue> vis = issues.visibleIssues(me, projectId);
        if (vis == null) {
            if (projectId != null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
            return Report.empty();
        }
        Instant now = Instant.now();
        Specification<Issue> unresolved = (r, q, cb) -> cb.lessThan(r.<Integer>get("status"), Lookups.RESOLVED);
        Specification<Issue> resolvedOnly = (r, q, cb) -> cb.and(cb.greaterThanOrEqualTo(r.<Integer>get("status"), Lookups.RESOLVED),
                cb.lessThan(r.<Integer>get("status"), Lookups.CLOSED));
        Specification<Issue> closedOnly = (r, q, cb) -> cb.greaterThanOrEqualTo(r.<Integer>get("status"), Lookups.CLOSED);

        return new Report(count(vis, null), count(vis, unresolved), count(vis, resolvedOnly), count(vis, closedOnly),
                byAttribute(vis, "status", k -> Lookups.label(Lookups.STATUS, k)),
                byAttribute(vis, "severity", k -> Lookups.label(Lookups.SEVERITY, k)),
                byAttribute(vis, "priority", k -> Lookups.label(Lookups.PRIORITY, k)),
                byAttribute(vis, "resolution", k -> Lookups.label(Lookups.RESOLUTION, k)),
                byCategory(vis), byProject(vis),
                people(vis, "handler", "[nobody]"), people(vis, "reporter", "[unknown]"),
                byDate(vis, now), openAge(vis, now), longestOpen(me, projectId, unresolved, now), mostActive(me, projectId));
    }

    // ------------------------------------------------------------------ plain counts
    private long count(Specification<Issue> vis, Specification<Issue> extra) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<Issue> root = cq.from(Issue.class);
        Predicate p = vis.toPredicate(root, cq, cb);
        if (extra != null) p = cb.and(p, extra.toPredicate(root, cq, cb));
        cq.select(cb.count(root)).where(p);
        return em.createQuery(cq).getSingleResult();
    }

    /** builds the select / group by / order by of a grouped query; the visibility WHERE is added by run() */
    private interface Shape { void apply(CriteriaBuilder cb, CriteriaQuery<Tuple> cq, Root<Issue> root); }

    private List<Tuple> run(Specification<Issue> vis, Shape shape, int max) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<Issue> root = cq.from(Issue.class);
        shape.apply(cb, cq, root);
        cq.where(vis.toPredicate(root, cq, cb));
        TypedQuery<Tuple> tq = em.createQuery(cq);
        if (max > 0) tq.setMaxResults(max);
        return tq.getResultList();
    }

    private static Expression<Long> sumIf(CriteriaBuilder cb, Predicate condition) {
        return cb.sum(cb.<Long>selectCase().when(condition, 1L).otherwise(0L));
    }
    private static Predicate isOpen(CriteriaBuilder cb, Root<Issue> r) { return cb.lessThan(r.<Integer>get("status"), Lookups.RESOLVED); }
    private static Predicate isResolved(CriteriaBuilder cb, Root<Issue> r) {
        return cb.and(cb.greaterThanOrEqualTo(r.<Integer>get("status"), Lookups.RESOLVED), cb.lessThan(r.<Integer>get("status"), Lookups.CLOSED));
    }
    private static Predicate isClosed(CriteriaBuilder cb, Root<Issue> r) { return cb.greaterThanOrEqualTo(r.<Integer>get("status"), Lookups.CLOSED); }

    // ------------------------------------------------------------------ grouped tables
    private List<Count> byAttribute(Specification<Issue> vis, String attribute, Function<Integer, String> label) {
        List<Tuple> rows = run(vis, (cb, cq, root) -> {
            Path<Integer> p = root.<Integer>get(attribute);
            cq.multiselect(p, cb.count(root), sumIf(cb, isOpen(cb, root))).groupBy(p).orderBy(cb.asc(p));
        }, 0);
        List<Count> out = new ArrayList<>();
        for (Tuple t : rows) {
            int k = t.get(0, Integer.class);
            out.add(new Count(String.valueOf(k), label.apply(k), t.get(1, Long.class), t.get(2, Long.class)));
        }
        return out;
    }

    /** grouped by category NAME, so the same name in many projects is one row */
    private List<Count> byCategory(Specification<Issue> vis) {
        List<Tuple> rows = run(vis, (cb, cq, root) -> {
            Join<Issue, Category> c = root.join("category", JoinType.LEFT);
            cq.multiselect(c.<String>get("name"), cb.count(root), sumIf(cb, isOpen(cb, root)))
                    .groupBy(c.<String>get("name")).orderBy(cb.desc(cb.count(root)));
        }, MAX_GROUPS);
        List<Count> out = new ArrayList<>();
        for (Tuple t : rows) {
            String name = t.get(0, String.class);
            out.add(new Count(name == null ? "" : name, name == null ? "(no category)" : name, t.get(1, Long.class), t.get(2, Long.class)));
        }
        return out;
    }

    private List<Count> byProject(Specification<Issue> vis) {
        List<Tuple> rows = run(vis, (cb, cq, root) -> {
            Join<Issue, Project> p = root.join("project");
            cq.multiselect(p.<Long>get("id"), p.<String>get("name"), cb.count(root), sumIf(cb, isOpen(cb, root)))
                    .groupBy(p.<Long>get("id"), p.<String>get("name")).orderBy(cb.desc(cb.count(root)));
        }, MAX_GROUPS);
        List<Count> out = new ArrayList<>();
        for (Tuple t : rows)
            out.add(new Count(String.valueOf(t.get(0, Long.class)), t.get(1, String.class), t.get(2, Long.class), t.get(3, Long.class)));
        return out;
    }

    /** by developer (association "handler") or by reporter ("reporter"): open / resolved / closed / total */
    private List<Person> people(Specification<Issue> vis, String association, String noOne) {
        List<Tuple> rows = run(vis, (cb, cq, root) -> {
            Join<Issue, User> u = root.join(association, JoinType.LEFT);
            cq.multiselect(u.<Long>get("id"), u.<String>get("username"), cb.count(root), sumIf(cb, isOpen(cb, root)),
                            sumIf(cb, isResolved(cb, root)), sumIf(cb, isClosed(cb, root)))
                    .groupBy(u.<Long>get("id"), u.<String>get("username")).orderBy(cb.desc(cb.count(root)));
        }, MAX_GROUPS);
        List<Person> out = new ArrayList<>();
        for (Tuple t : rows) {
            String name = t.get(1, String.class);
            out.add(new Person(t.get(0, Long.class), name == null ? noOne : name, t.get(3, Long.class), t.get(4, Long.class),
                    t.get(5, Long.class), t.get(2, Long.class)));
        }
        return out;
    }

    // ------------------------------------------------------------------ opened / resolved in the last N days
    private List<Day> byDate(Specification<Issue> vis, Instant now) {
        List<Day> out = new ArrayList<>();
        for (int days : SummaryReport.WINDOW_DAYS) {
            Instant since = now.minus(Duration.ofDays(days));
            long opened = count(vis, (r, q, cb) -> cb.greaterThanOrEqualTo(r.<Instant>get("dateSubmitted"), since));
            // "resolved" = the history shows a change of the status to resolved / closed inside the window
            long resolved = count(vis, (r, q, cb) -> {
                Subquery<Long> sq = q.subquery(Long.class);
                Root<IssueHistory> h = sq.from(IssueHistory.class);
                sq.select(h.get("issue").<Long>get("id")).where(cb.equal(h.get("fieldName"), "status"),
                        h.<String>get("newValue").in("80", "90"), cb.greaterThanOrEqualTo(h.<Instant>get("dateModified"), since));
                return r.get("id").in(sq);
            });
            out.add(new Day(days, opened, resolved));
        }
        return out;
    }

    private List<Age> openAge(Specification<Issue> vis, Instant now) {
        List<Age> out = new ArrayList<>();
        for (Bucket b : SummaryReport.AGE_BUCKETS) {
            Instant older = SummaryReport.olderBound(b, now), younger = SummaryReport.youngerBound(b, now);
            long n = count(vis, (r, q, cb) -> {
                List<Predicate> ps = new ArrayList<>();
                ps.add(cb.lessThan(r.<Integer>get("status"), Lookups.RESOLVED));
                ps.add(cb.lessThanOrEqualTo(r.<Instant>get("dateSubmitted"), older));
                if (younger != null) ps.add(cb.greaterThan(r.<Instant>get("dateSubmitted"), younger));
                return cb.and(ps.toArray(new Predicate[0]));
            });
            out.add(new Age(b.label(), n));
        }
        return out;
    }

    // ------------------------------------------------------------------ top lists
    private List<OldIssue> longestOpen(User me, Long projectId, Specification<Issue> unresolved, Instant now) {
        Page<Issue> page = issues.search(me, new IssueService.Filter(projectId, null, null, null, null, null, null, null, false, null),
                unresolved, PageRequest.of(0, TOP, Sort.by(Sort.Direction.ASC, "dateSubmitted").and(Sort.by("id"))));
        List<OldIssue> out = new ArrayList<>();
        for (Issue i : page.getContent())
            out.add(new OldIssue(i.getId(), i.getSummary(), i.getStatus(), i.getHandler() == null ? "" : i.getHandler().getUsername(),
                    SummaryReport.ageDays(i.getDateSubmitted(), now)));
        return out;
    }

    /** issues with the most (public) notes. Candidates come from one GROUP BY, then the visibility rules decide what is shown. */
    private List<ActiveIssue> mostActive(User me, Long projectId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<Note> n = cq.from(Note.class);
        cq.multiselect(n.get("issue").<Long>get("id"), cb.count(n)).where(cb.equal(n.get("viewState"), Lookups.VS_PUBLIC))
                .groupBy(n.get("issue").<Long>get("id")).orderBy(cb.desc(cb.count(n)));
        List<Tuple> top = em.createQuery(cq).setMaxResults(200).getResultList();
        if (top.isEmpty()) return List.of();

        Map<Long, Long> notes = new LinkedHashMap<>();
        for (Tuple t : top) notes.put(t.get(0, Long.class), t.get(1, Long.class));
        Page<Issue> visible = issues.search(me, new IssueService.Filter(projectId, null, null, null, null, null, null, null, false, null),
                (r, q, c) -> r.get("id").in(notes.keySet()), PageRequest.of(0, 200, Sort.by("id")));
        Map<Long, Issue> byId = new HashMap<>();
        visible.getContent().forEach(i -> byId.put(i.getId(), i));

        List<ActiveIssue> out = new ArrayList<>();
        for (Map.Entry<Long, Long> e : notes.entrySet()) {
            Issue i = byId.get(e.getKey());
            if (i == null) continue;
            out.add(new ActiveIssue(i.getId(), i.getSummary(), i.getStatus(), e.getValue()));
            if (out.size() >= TOP) break;
        }
        return out;
    }
}
