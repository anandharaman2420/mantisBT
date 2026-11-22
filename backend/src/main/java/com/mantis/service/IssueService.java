package com.mantis.service;

import com.mantis.domain.*;
import com.mantis.dto.Dtos.*;
import com.mantis.repo.*;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Port of Mantis bug_api.php (+ the permission rules of access_api / config thresholds). */
@Service
@Transactional
public class IssueService {
    private static final Set<String> LONG_TEXT = Set.of("description", "steps_to_reproduce", "additional_information");

    private final IssueRepository issues;
    private final CategoryRepository categories;
    private final UserRepository users;
    private final TagRepository tags;
    private final NoteRepository notes;
    private final AttachmentRepository attachments;
    private final HistoryRepository historyRepo;
    private final MonitorRepository monitors;
    private final RelationshipRepository relationships;
    private final AccessService access;
    private final HistoryService history;
    private final NotificationService notifier;
    private final boolean trackTime;
    private final CustomFieldService customFields;
    private final CustomFieldValueRepository fieldValues;

    public IssueService(IssueRepository issues, CategoryRepository categories, UserRepository users, TagRepository tags,
                        NoteRepository notes, AttachmentRepository attachments, HistoryRepository historyRepo,
                        MonitorRepository monitors, RelationshipRepository relationships, AccessService access,
                        HistoryService history, NotificationService notifier,
                        CustomFieldService customFields, CustomFieldValueRepository fieldValues,
                        @org.springframework.beans.factory.annotation.Value("${app.time-tracking.enabled}") boolean trackTime) {
        this.issues = issues; this.categories = categories; this.users = users; this.tags = tags;
        this.notes = notes; this.attachments = attachments; this.historyRepo = historyRepo; this.monitors = monitors;
        this.relationships = relationships; this.access = access; this.history = history; this.notifier = notifier;
        this.customFields = customFields; this.fieldValues = fieldValues; this.trackTime = trackTime;
    }

    // ------------------------------------------------------------------ errors
    static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }
    static ResponseStatusException denied() { return new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied"); }
    static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Issue not found"); }

    // ------------------------------------------------------------------ visibility
    public boolean canSee(User me, Issue i) { return access.canSee(me, i); }

    /** 404 (not 403) for invisible issues so that ids cannot be probed. */
    public Issue getVisible(User me, Long id) {
        Issue i = issues.findById(id).orElseThrow(IssueService::notFound);
        if (!canSee(me, i)) throw notFound();
        return i;
    }

    /** Visibility check + DTO mapping in one transaction (used by the REST/SOAP compatibility layer). */
    @Transactional(readOnly = true)
    public IssueView viewById(User me, Long id) {
        return view(me, getVisible(me, id));
    }

    /** Search that maps to DTOs inside the transaction (used by the REST compatibility layer). */
    @Transactional(readOnly = true)
    public Page<IssueView> searchViews(User me, Filter f, Pageable pg) {
        return search(me, f, pg).map(i -> view(me, i));
    }

    // ------------------------------------------------------------------ search (view_all_bug_page / filter_api)
    public record Filter(Long projectId, List<Integer> status, Long handlerId, Long reporterId, Integer priority,
                         Integer severity, Long categoryId, String search, boolean hideClosed, String tag) {}

    @Transactional(readOnly = true)
    public Page<Issue> search(User me, Filter f, Pageable pg) {
        Specification<Issue> spec = buildSpecification(me, f);
        if (spec == null) return Page.empty(pg);
        return issues.findAll(spec, pg);
    }

    @Transactional(readOnly = true)
    public List<Issue> searchAll(User me, Filter f, org.springframework.data.domain.Sort sort) {
        Specification<Issue> spec = buildSpecification(me, f);
        if (spec == null) return List.of();
        return issues.findAll(spec, sort);
    }

    private Specification<Issue> buildSpecification(User me, Filter f) {
        Map<Long, Integer> levels = access.levels(me);
        if (f.projectId() != null && !levels.containsKey(f.projectId())) return null;
        if (levels.isEmpty()) return null;
        Set<Long> devProjects = levels.entrySet().stream().filter(e -> e.getValue() >= Lookups.DEVELOPER)
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        // selecting a project also shows the issues of its sub-projects (that the user may see)
        final Set<Long> scope = f.projectId() == null ? levels.keySet() : new HashSet<>(access.subtreeIds(f.projectId()));
        if (f.projectId() != null) scope.retainAll(levels.keySet());

        return (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            Join<Issue, User> handlerJoin = root.join("handler", JoinType.LEFT);   // handler may be NULL
            Path<Long> pid = root.get("project").get("id");
            ps.add(pid.in(scope));

            // private issues: reporter, handler, or developer+ in that project
            List<Predicate> vis = new ArrayList<>();
            vis.add(cb.equal(root.get("viewState"), Lookups.VS_PUBLIC));
            vis.add(cb.equal(root.get("reporter").get("id"), me.getId()));
            vis.add(cb.equal(handlerJoin.get("id"), me.getId()));
            if (!devProjects.isEmpty()) vis.add(pid.in(devProjects));
            ps.add(cb.or(vis.toArray(new Predicate[0])));

            if (f.status() != null && !f.status().isEmpty()) ps.add(root.get("status").in(f.status()));
            else if (f.hideClosed()) ps.add(cb.lessThan(root.<Integer>get("status"), Lookups.CLOSED));
            if (f.handlerId() != null) {
                ps.add(f.handlerId() == 0 ? cb.isNull(handlerJoin.get("id")) : cb.equal(handlerJoin.get("id"), f.handlerId()));
            }
            if (f.reporterId() != null) ps.add(cb.equal(root.get("reporter").get("id"), f.reporterId()));
            if (f.priority() != null) ps.add(cb.equal(root.get("priority"), f.priority()));
            if (f.severity() != null) ps.add(cb.equal(root.get("severity"), f.severity()));
            if (f.categoryId() != null) ps.add(cb.equal(root.get("category").get("id"), f.categoryId()));
            if (f.tag() != null && !f.tag().isBlank()) {
                Join<Issue, Tag> t = root.join("tags");
                ps.add(cb.equal(cb.lower(t.get("name")), f.tag().toLowerCase()));
            }
            if (f.search() != null && !f.search().isBlank()) {
                String like = "%" + f.search().trim().toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%";
                List<Predicate> or = new ArrayList<>();
                or.add(cb.like(cb.lower(root.get("summary")), like, '\\'));
                or.add(cb.like(cb.lower(root.get("description")), like, '\\'));
                or.add(cb.like(cb.lower(root.get("additionalInformation")), like, '\\'));
                try { or.add(cb.equal(root.get("id"), Long.parseLong(f.search().trim()))); } catch (NumberFormatException ignored) {}
                ps.add(cb.or(or.toArray(new Predicate[0])));
            }
            if (f.tag() != null && !f.tag().isBlank()) q.distinct(true);
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    // ------------------------------------------------------------------ view
    @Transactional(readOnly = true)
    public IssueView view(User me, Issue i) {
        int lvl = access.levelIn(me, i.getProject());
        boolean readonly = i.getStatus() >= Lookups.RESOLVED;
        boolean canUpdate = lvl >= Lookups.UPDATER && (!readonly || lvl >= Lookups.MANAGER);
        List<IssueMonitor> ms = monitors.findByIssueId(i.getId());
        boolean monitoring = ms.stream().anyMatch(m -> m.getUser().getId().equals(me.getId()));
        List<UserRef> monitorRefs = lvl >= Lookups.DEVELOPER
                ? ms.stream().map(m -> Mapper.ref(m.getUser())).toList() : List.of();
        List<String> tagNames = i.getTags().stream().map(Tag::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        return new IssueView(i.getId(), i.getProject().getId(), i.getProject().getName(),
                i.getCategory() == null ? null : i.getCategory().getId(),
                i.getCategory() == null ? null : i.getCategory().getName(),
                i.getSummary(), i.getDescription(), i.getStepsToReproduce(), i.getAdditionalInformation(),
                i.getStatus(), i.getResolution(), i.getPriority(), i.getSeverity(), i.getReproducibility(),
                i.getProjection(), i.getEta(), Mapper.ref(i.getReporter()), Mapper.ref(i.getHandler()),
                i.getDuplicateId(), i.getOs(), i.getOsBuild(), i.getPlatform(), i.getVersion(),
                i.getFixedInVersion(), i.getTargetVersion(), i.getBuild(), i.getViewState(), i.isSticky(),
                i.getDateSubmitted(), i.getLastUpdated(), tagNames, monitorRefs, monitoring,
                canUpdate, lvl >= Lookups.DEVELOPER, lvl >= Lookups.DEVELOPER,
                lvl >= Lookups.REPORTER && (!readonly || lvl >= Lookups.MANAGER), lvl >= Lookups.REPORTER,
                customFields.forIssue(i, lvl),
                trackTime && lvl >= Lookups.DEVELOPER ? (int) notes.sumMinutes(i.getId()) : 0);
    }

    // ------------------------------------------------------------------ create (bug_report.php / bug_api bug_create)
    public Issue create(User me, IssueForm f) {
        if (f.projectId() == null) throw bad("projectId is required");
        Project p = access.projectOrThrow(f.projectId());
        int lvl = access.require(me, p, Lookups.REPORTER);
        if (blank(f.summary())) throw bad("summary is required");
        if (blank(f.description())) throw bad("description is required");

        Issue i = new Issue();
        i.setProject(p);
        i.setReporter(me);
        i.setSummary(f.summary().trim());
        i.setDescription(f.description());
        if (f.stepsToReproduce() != null) i.setStepsToReproduce(f.stepsToReproduce());
        if (f.additionalInformation() != null) i.setAdditionalInformation(f.additionalInformation());
        if (f.priority() != null) i.setPriority(valid(Lookups.PRIORITY, f.priority(), "priority"));
        if (f.severity() != null) i.setSeverity(valid(Lookups.SEVERITY, f.severity(), "severity"));
        if (f.reproducibility() != null) i.setReproducibility(valid(Lookups.REPRODUCIBILITY, f.reproducibility(), "reproducibility"));
        if (f.projection() != null) i.setProjection(valid(Lookups.PROJECTION, f.projection(), "projection"));
        if (f.eta() != null) i.setEta(valid(Lookups.ETA, f.eta(), "eta"));
        if (f.viewState() != null) i.setViewState(valid(Lookups.VIEW_STATE, f.viewState(), "viewState"));
        if (f.os() != null) i.setOs(f.os());
        if (f.osBuild() != null) i.setOsBuild(f.osBuild());
        if (f.platform() != null) i.setPlatform(f.platform());
        if (f.version() != null) i.setVersion(f.version());
        if (f.targetVersion() != null && lvl >= Lookups.DEVELOPER) i.setTargetVersion(f.targetVersion());
        if (f.build() != null) i.setBuild(f.build());
        if (f.sticky() != null && lvl >= Lookups.MANAGER) i.setSticky(f.sticky());

        if (f.categoryId() != null) i.setCategory(categoryOf(p, f.categoryId()));

        if (f.handlerId() != null) {
            if (lvl < Lookups.DEVELOPER) throw denied();
            i.setHandler(handlerFor(p, f.handlerId()));
        } else if (i.getCategory() != null && i.getCategory().getDefaultHandler() != null
                && access.levelIn(i.getCategory().getDefaultHandler(), p) >= Lookups.DEVELOPER) {
            i.setHandler(i.getCategory().getDefaultHandler());          // Mantis: category default assignee
        }
        if (i.getHandler() != null) i.setStatus(Lookups.ASSIGNED);       // $g_auto_set_status_to_assigned

        issues.save(i);
        history.log(me, i, "", null, null, Lookups.H_NEW_BUG);
        if (f.tags() != null) syncTags(me, i, f.tags(), false);
        customFields.apply(me, i, f.customFields(), true, lvl);
        notifier.issueCreated(me, i);
        return i;
    }

    // ------------------------------------------------------------------ update (bug_update.php)
    public Issue update(User me, Long id, IssueForm f) {
        history.begin();
        try {
            Issue i = doUpdate(me, id, f);
            notifier.issueUpdated(me, i, history.changes(), null);
            return i;
        } finally {
            history.end();
        }
    }

    private Issue doUpdate(User me, Long id, IssueForm f) {
        Issue i = getVisible(me, id);
        Project p = i.getProject();
        int lvl = access.require(me, p, Lookups.UPDATER);
        if (i.getStatus() >= Lookups.RESOLVED && lvl < Lookups.MANAGER)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Issue is read-only (resolved/closed). Reopen it first.");

        // plain fields
        upd(me, i, "summary", i.getSummary(), f.summary() == null ? null : f.summary().trim(), i::setSummary);
        upd(me, i, "description", i.getDescription(), f.description(), i::setDescription);
        upd(me, i, "steps_to_reproduce", i.getStepsToReproduce(), f.stepsToReproduce(), i::setStepsToReproduce);
        upd(me, i, "additional_information", i.getAdditionalInformation(), f.additionalInformation(), i::setAdditionalInformation);
        upd(me, i, "priority", i.getPriority(), f.priority() == null ? null : valid(Lookups.PRIORITY, f.priority(), "priority"), i::setPriority);
        upd(me, i, "severity", i.getSeverity(), f.severity() == null ? null : valid(Lookups.SEVERITY, f.severity(), "severity"), i::setSeverity);
        upd(me, i, "reproducibility", i.getReproducibility(), f.reproducibility() == null ? null : valid(Lookups.REPRODUCIBILITY, f.reproducibility(), "reproducibility"), i::setReproducibility);
        upd(me, i, "projection", i.getProjection(), f.projection() == null ? null : valid(Lookups.PROJECTION, f.projection(), "projection"), i::setProjection);
        upd(me, i, "eta", i.getEta(), f.eta() == null ? null : valid(Lookups.ETA, f.eta(), "eta"), i::setEta);
        upd(me, i, "os", i.getOs(), f.os(), i::setOs);
        upd(me, i, "os_build", i.getOsBuild(), f.osBuild(), i::setOsBuild);
        upd(me, i, "platform", i.getPlatform(), f.platform(), i::setPlatform);
        upd(me, i, "version", i.getVersion(), f.version(), i::setVersion);
        upd(me, i, "build", i.getBuild(), f.build(), i::setBuild);
        upd(me, i, "view_state", i.getViewState(), f.viewState() == null ? null : valid(Lookups.VIEW_STATE, f.viewState(), "viewState"), i::setViewState);

        // Empty version values mean "clear" in bulk and move/copy-style update forms.
        // Null means unchanged, so normalize only non-null values after the DTO is mapped.
        String fixedInVersion = f.fixedInVersion() == null ? null : (f.fixedInVersion().trim().isEmpty() ? null : f.fixedInVersion().trim());
        String targetVersion = f.targetVersion() == null ? null : (f.targetVersion().trim().isEmpty() ? null : f.targetVersion().trim());

        if (f.categoryId() != null) {
            Long old = i.getCategory() == null ? null : i.getCategory().getId();
            if (!f.categoryId().equals(old)) {
                Category c = categoryOf(p, f.categoryId());
                history.log(me, i, "category_id", old, c.getId());
                i.setCategory(c);
            }
        }
        // developer-only fields
        boolean devFieldTouched =
                (fixedInVersion != null && !fixedInVersion.equals(i.getFixedInVersion()))
                || (f.fixedInVersion() != null && fixedInVersion == null && i.getFixedInVersion() != null)
                || (targetVersion != null && !targetVersion.equals(i.getTargetVersion()))
                || (f.targetVersion() != null && targetVersion == null && i.getTargetVersion() != null)
                || (f.sticky() != null && f.sticky() != i.isSticky())
                || (f.status() != null && f.status() != i.getStatus())
                || (f.resolution() != null && f.resolution() != i.getResolution())
                || (f.handlerId() != null && (i.getHandler() == null || !i.getHandler().getId().equals(f.handlerId())))
                || Boolean.TRUE.equals(f.clearHandler())
                || (f.duplicateId() != null && !Objects.equals(f.duplicateId() <= 0 ? null : f.duplicateId(), i.getDuplicateId()));
        if (devFieldTouched) {
            if (lvl < Lookups.DEVELOPER) {
                // reporters-with-update rights may only touch nothing here; sticky needs manager
                throw denied();
            }
            upd(me, i, "fixed_in_version", i.getFixedInVersion(), fixedInVersion, i::setFixedInVersion);
            upd(me, i, "target_version", i.getTargetVersion(), targetVersion, i::setTargetVersion);
            if (f.sticky() != null) {
                if (lvl < Lookups.MANAGER) throw denied();
                upd(me, i, "sticky", i.isSticky(), f.sticky(), i::setSticky);
            }
            if (f.duplicateId() != null) {
                if (f.duplicateId() > 0) {
                    Issue dup = issues.findById(f.duplicateId()).orElseThrow(() -> bad("Duplicate issue not found"));
                    if (dup.getId().equals(i.getId())) throw bad("An issue cannot be a duplicate of itself");
                }
                if (f.duplicateId() <= 0) {
                    if (i.getDuplicateId() != null) { history.log(me, i, "duplicate_id", i.getDuplicateId(), null); i.setDuplicateId(null); }
                } else {
                    upd(me, i, "duplicate_id", i.getDuplicateId(), f.duplicateId(), i::setDuplicateId);
                }
            }
            applyWorkflow(me, i, lvl, f.status(), f.resolution(), f.handlerId(), Boolean.TRUE.equals(f.clearHandler()));
        }
        if (f.tags() != null) {
            if (lvl < Lookups.UPDATER) throw denied();
            syncTags(me, i, f.tags(), true);
        }
        customFields.apply(me, i, f.customFields(), false, lvl);
        i.setLastUpdated(Instant.now());
        return issues.save(i);
    }

    /** bug_api "change status" + assign: status, resolution, handler rules in one place. */
    public Issue changeStatus(User me, Long id, StatusChange c) {
        history.begin();
        try {
            Issue i = doChangeStatus(me, id, c);
            notifier.issueUpdated(me, i, history.changes(), c.note());
            return i;
        } finally {
            history.end();
        }
    }

    private Issue doChangeStatus(User me, Long id, StatusChange c) {
        Issue i = getVisible(me, id);
        int lvl = access.require(me, i.getProject(), Lookups.DEVELOPER);
        if (i.getStatus() >= Lookups.RESOLVED && lvl < Lookups.MANAGER)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Issue is read-only (resolved/closed)");
        if (c.duplicateId() != null) {
            Issue dup = issues.findById(c.duplicateId()).orElseThrow(() -> bad("Duplicate issue not found"));
            if (dup.getId().equals(i.getId())) throw bad("An issue cannot be a duplicate of itself");
            upd(me, i, "duplicate_id", i.getDuplicateId(), c.duplicateId(), i::setDuplicateId);
        }
        applyWorkflow(me, i, lvl, c.status(), c.resolution(), c.handlerId(), false);
        if (!blank(c.note())) {
            Note n = new Note();
            n.setIssue(i); n.setReporter(me); n.setText(c.note());
            notes.save(n);
            history.log(me, i, "", null, n.getId(), Lookups.H_NOTE_ADDED);
        }
        i.setLastUpdated(Instant.now());
        return issues.save(i);
    }

    private void applyWorkflow(User me, Issue i, int lvl, Integer newStatus, Integer newResolution,
                               Long newHandlerId, boolean clearHandler) {
        int oldStatus = i.getStatus();
        boolean handlerChanged = false;

        if (clearHandler && i.getHandler() != null) {
            history.log(me, i, "handler_id", i.getHandler().getId(), null);
            i.setHandler(null);
            handlerChanged = true;
        } else if (newHandlerId != null && (i.getHandler() == null || !i.getHandler().getId().equals(newHandlerId))) {
            User h = handlerFor(i.getProject(), newHandlerId);
            history.log(me, i, "handler_id", i.getHandler() == null ? null : i.getHandler().getId(), h.getId());
            i.setHandler(h);
            handlerChanged = true;
        }

        Integer target = newStatus;
        if (target == null && handlerChanged && i.getHandler() != null && oldStatus < Lookups.ASSIGNED)
            target = Lookups.ASSIGNED;                                   // auto-set to assigned
        if (target != null) valid(Lookups.STATUS, target, "status");

        boolean reopen = target != null && oldStatus >= Lookups.RESOLVED && target < Lookups.RESOLVED;
        if (target != null && target == Lookups.ASSIGNED && i.getHandler() == null) {
            i.setHandler(me);                                            // Mantis assigns to the acting user
            history.log(me, i, "handler_id", null, me.getId());
        }
        if (target != null && target != oldStatus) {
            history.log(me, i, "status", oldStatus, target);
            i.setStatus(target);
        }

        Integer res = newResolution;
        if (res == null && reopen) res = Lookups.RES_REOPENED;
        if (res == null && target != null && target == Lookups.RESOLVED && i.getResolution() == Lookups.RES_OPEN)
            res = Lookups.RES_FIXED;
        if (res != null) {
            valid(Lookups.RESOLUTION, res, "resolution");
            if (res != i.getResolution()) {
                history.log(me, i, "resolution", i.getResolution(), res);
                i.setResolution(res);
            }
        }
    }

    // ------------------------------------------------------------------ move / copy (bug_move / bug_copy)
    /** Moves an issue to another project: category is mapped by name, handler is dropped if not a developer there. */
    public Issue move(User me, Long id, Long targetProjectId) {
        history.begin();
        try {
            Issue i = getVisible(me, id);
            Project from = i.getProject();
            Project to = access.projectOrThrow(targetProjectId);
            if (from.getId().equals(to.getId())) throw bad("The issue is already in this project");
            int lvl = access.require(me, from, Lookups.DEVELOPER);
            access.require(me, to, Lookups.REPORTER);
            if (i.getStatus() >= Lookups.RESOLVED && lvl < Lookups.MANAGER)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Issue is read-only (resolved/closed)");

            history.log(me, i, "project_id", from.getId(), to.getId());
            i.setProject(to);

            Category oldCat = i.getCategory();
            if (oldCat != null) {
                Category mapped = categoryByName(to, oldCat.getName());
                history.log(me, i, "category_id", oldCat.getId(), mapped == null ? null : mapped.getId());
                i.setCategory(mapped);
            }
            if (i.getHandler() != null && access.levelIn(i.getHandler(), to) < Lookups.DEVELOPER) {
                history.log(me, i, "handler_id", i.getHandler().getId(), null);
                i.setHandler(null);
                if (i.getStatus() == Lookups.ASSIGNED) {
                    history.log(me, i, "status", Lookups.ASSIGNED, Lookups.NEW);
                    i.setStatus(Lookups.NEW);
                }
            }
            customFields.afterMove(i, to);
            i.setLastUpdated(Instant.now());
            issues.save(i);
            notifier.issueUpdated(me, i, history.changes(), null);
            return i;
        } finally {
            history.end();
        }
    }

    /** Clones an issue (new id, you are the reporter, status "new", unassigned). Returns the new issue. */
    public Issue copy(User me, Long id, CopyForm f) {
        Issue src = getVisible(me, id);
        Project to = f.projectId() == null ? src.getProject() : access.projectOrThrow(f.projectId());
        access.require(me, to, Lookups.REPORTER);
        boolean sameProject = to.getId().equals(src.getProject().getId());

        Issue n = new Issue();
        n.setProject(to);
        n.setReporter(me);
        n.setSummary(src.getSummary());
        n.setDescription(src.getDescription());
        n.setStepsToReproduce(src.getStepsToReproduce());
        n.setAdditionalInformation(src.getAdditionalInformation());
        n.setPriority(src.getPriority()); n.setSeverity(src.getSeverity()); n.setReproducibility(src.getReproducibility());
        n.setProjection(src.getProjection()); n.setEta(src.getEta());
        n.setOs(src.getOs()); n.setOsBuild(src.getOsBuild()); n.setPlatform(src.getPlatform());
        n.setViewState(src.getViewState());
        if (sameProject) { n.setVersion(src.getVersion()); n.setBuild(src.getBuild()); n.setTargetVersion(src.getTargetVersion()); }
        if (src.getCategory() != null) n.setCategory(categoryByName(to, src.getCategory().getName()));
        n.getTags().addAll(src.getTags());
        issues.save(n);
        history.log(me, n, "", null, null, Lookups.H_NEW_BUG);
        customFields.copyValues(src, n, to);

        int srcLvl = access.levelIn(me, src.getProject());
        if (Boolean.TRUE.equals(f.copyNotes())) {
            for (Note o : notes.findByIssueIdOrderByDateSubmittedAsc(src.getId())) {
                boolean own = o.getReporter().getId().equals(me.getId());
                if (o.getViewState() == Lookups.VS_PRIVATE && srcLvl < Lookups.DEVELOPER && !own) continue;
                Note c = new Note();
                c.setIssue(n); c.setReporter(o.getReporter()); c.setText(o.getText()); c.setViewState(o.getViewState());
                c.setNoteType(o.getNoteType()); c.setDateSubmitted(o.getDateSubmitted()); c.setLastModified(o.getLastModified());
                notes.save(c);
            }
        }
        if (Boolean.TRUE.equals(f.copyAttachments())) {
            for (Attachment o : attachments.findByIssueId(src.getId())) {
                Attachment c = new Attachment();
                c.setIssue(n); c.setUser(o.getUser()); c.setTitle(o.getTitle()); c.setDescription(o.getDescription());
                c.setFilename(o.getFilename()); c.setFilesize(o.getFilesize()); c.setFileType(o.getFileType());
                c.setDateAdded(o.getDateAdded()); c.setContent(o.getContent());
                attachments.save(c);
            }
        }
        if (f.linkToOriginal() == null || f.linkToOriginal()) {
            IssueRelationship r = new IssueRelationship();
            r.setSource(n); r.setDestination(src); r.setRelationshipType(1);     // related to
            relationships.save(r);
            history.log(me, n, "", null, src.getId() + ":" + Lookups.label(Lookups.RELATIONSHIP, 1), Lookups.H_REL_ADDED);
            history.log(me, src, "", null, n.getId() + ":" + Lookups.label(Lookups.RELATIONSHIP, 1), Lookups.H_REL_ADDED);
        }
        notifier.issueCreated(me, n);
        return n;
    }

    /** category with this name in the project or (inherited) in its parent projects; null if none */
    public Category categoryByName(Project p, String name) {
        List<Project> chain = new ArrayList<>(List.of(p));
        chain.addAll(access.inheritedAncestors(p));
        for (Project x : chain) {
            Category c = categories.findByProjectIdOrderByName(x.getId()).stream()
                    .filter(k -> k.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
            if (c != null) return c;
        }
        return null;
    }

    // ------------------------------------------------------------------ delete
    public void delete(User me, Long id) {
        Issue i = getVisible(me, id);
        access.require(me, i.getProject(), Lookups.DEVELOPER);           // delete_bug_threshold
        relationships.deleteAllFor(i.getId());
        fieldValues.deleteByIssueId(i.getId());
        notes.deleteByIssueId(i.getId());
        attachments.deleteByIssueId(i.getId());
        monitors.deleteByIssueId(i.getId());
        historyRepo.deleteByIssueId(i.getId());
        i.getTags().clear();
        issues.delete(i);
    }

    // ------------------------------------------------------------------ dashboard
    @Transactional(readOnly = true)
    public SummaryView summary(User me, Long projectId) {
        Map<Long, Integer> levels = access.levels(me);
        Set<Long> ids = levels.keySet();
        if (projectId != null) {
            ids = new HashSet<>(levels.containsKey(projectId) ? access.subtreeIds(projectId) : Set.of());
            ids.retainAll(levels.keySet());
        }
        if (ids.isEmpty()) return new SummaryView(0, List.of(), List.of(), List.of(), 0, 0);
        List<CountEntry> st = counts(issues.countByStatus(ids), Lookups.STATUS);
        long total = st.stream().mapToLong(CountEntry::count).sum();
        long open = st.stream().filter(c -> c.key() < Lookups.RESOLVED).mapToLong(CountEntry::count).sum();
        return new SummaryView(total, st, counts(issues.countOpenByPriority(ids), Lookups.PRIORITY),
                counts(issues.countOpenBySeverity(ids), Lookups.SEVERITY), issues.countUnassigned(ids), open);
    }

    private static List<CountEntry> counts(List<Object[]> rows, Map<Integer, String> labels) {
        return rows.stream().map(r -> new CountEntry(((Number) r[0]).intValue(),
                        Lookups.label(labels, ((Number) r[0]).intValue()), ((Number) r[1]).longValue()))
                .sorted(Comparator.comparingInt(CountEntry::key)).toList();
    }

    // ------------------------------------------------------------------ helpers
    private <T> void upd(User me, Issue i, String field, T oldV, T newV, Consumer<T> setter) {
        if (newV == null || Objects.equals(oldV, newV)) return;
        if (LONG_TEXT.contains(field)) history.log(me, i, field, null, null);   // keep history small: just mark it
        else history.log(me, i, field, oldV, newV);
        setter.accept(newV);
    }

    private Category categoryOf(Project p, Long categoryId) {
        Category c = categories.findById(categoryId).orElseThrow(() -> bad("Category not found"));
        if (!categoryUsable(p, c)) throw bad("Category does not belong to this project");
        return c;
    }

    /** a category is usable in a project if it belongs to it or to a parent project it inherits from */
    public boolean categoryUsable(Project p, Category c) {
        Long owner = c.getProject().getId();
        return owner.equals(p.getId()) || access.inheritedAncestors(p).stream().anyMatch(a -> a.getId().equals(owner));
    }

    private User handlerFor(Project p, Long userId) {
        User h = users.findById(userId).orElseThrow(() -> bad("Handler not found"));
        if (!h.isEnabled() || access.levelIn(h, p) < Lookups.DEVELOPER)
            throw bad("Selected user cannot handle issues in this project");
        return h;
    }

    private void syncTags(User me, Issue i, List<String> names, boolean log) {
        Set<String> wanted = names.stream().map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(() -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)));
        for (Iterator<Tag> it = i.getTags().iterator(); it.hasNext(); ) {
            Tag t = it.next();
            if (!wanted.contains(t.getName())) { it.remove(); if (log) history.log(me, i, "", t.getName(), null, Lookups.H_TAG_DETACHED); }
        }
        Set<String> have = i.getTags().stream().map(t -> t.getName().toLowerCase()).collect(Collectors.toSet());
        for (String n : wanted) {
            if (have.contains(n.toLowerCase())) continue;
            if (n.length() > 100) throw bad("Tag name too long");
            Tag t = tags.findByNameIgnoreCase(n).orElseGet(() -> {
                Tag nt = new Tag(); nt.setName(n); nt.setCreator(me); return tags.save(nt);
            });
            i.getTags().add(t);
            if (log) history.log(me, i, "", null, t.getName(), Lookups.H_TAG_ATTACHED);
        }
    }

    static int valid(Map<Integer, String> allowed, int v, String field) {
        if (!allowed.containsKey(v)) throw bad("Invalid value for " + field + ": " + v);
        return v;
    }

    static boolean blank(String s) { return s == null || s.isBlank(); }
}
