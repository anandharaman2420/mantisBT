package com.mantis.service;

import com.mantis.config.ApiProperties;
import com.mantis.domain.*;
import com.mantis.dto.Dtos.*;
import com.mantis.repo.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/** Notes (bugnote_api), attachments (file_api), monitors, relationships (relationship_api), history views. */
@Service
@Transactional
public class IssueExtrasService {
    private final IssueService issueService;
    private final IssueRepository issues;
    private final NoteRepository notes;
    private final AttachmentRepository attachments;
    private final HistoryRepository historyRepo;
    private final MonitorRepository monitors;
    private final RelationshipRepository relationships;
    private final UserRepository users;
    private final CategoryRepository categories;
    private final AccessService access;
    private final HistoryService history;
    private final NotificationService notifier;
    private final ProjectRepository projects;
    private final boolean trackTime;
    private final ApiProperties api;

    public IssueExtrasService(IssueService issueService, IssueRepository issues, NoteRepository notes,
                              AttachmentRepository attachments, HistoryRepository historyRepo, MonitorRepository monitors,
                              RelationshipRepository relationships, UserRepository users, CategoryRepository categories,
                              AccessService access, HistoryService history, NotificationService notifier,
                              ProjectRepository projects, ApiProperties api,
                              @org.springframework.beans.factory.annotation.Value("${app.time-tracking.enabled}") boolean trackTime) {
        this.issueService = issueService; this.issues = issues; this.notes = notes; this.attachments = attachments;
        this.historyRepo = historyRepo; this.monitors = monitors; this.relationships = relationships;
        this.users = users; this.categories = categories; this.access = access; this.history = history; this.notifier = notifier; this.projects = projects; this.trackTime = trackTime;
        this.api = api;
    }

    private ResponseStatusException denied() { return new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied"); }
    private ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }

    // ================================================================== notes
    @Transactional(readOnly = true)
    public List<NoteView> listNotes(User me, Long issueId) {
        Issue i = issueService.getVisible(me, issueId);
        int lvl = access.levelIn(me, i.getProject());
        List<NoteView> out = new ArrayList<>();
        for (Note n : notes.findByIssueIdOrderByDateSubmittedAsc(issueId)) {
            boolean own = n.getReporter().getId().equals(me.getId());
            if (n.getViewState() == Lookups.VS_PRIVATE && lvl < Lookups.DEVELOPER && !own) continue;
            out.add(noteView(n, canEditNote(me, lvl, n), lvl >= Lookups.DEVELOPER));
        }
        return out;
    }

    public NoteView addNote(User me, Long issueId, NoteForm f) {
        Issue i = issueService.getVisible(me, issueId);
        int lvl = access.require(me, i.getProject(), Lookups.REPORTER);
        if (i.getStatus() >= Lookups.RESOLVED && lvl < Lookups.MANAGER)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Issue is read-only (resolved/closed)");
        Note n = new Note();
        n.setIssue(i);
        n.setReporter(me);
        n.setText(f.text());
        int vs = f.viewState() == null ? Lookups.VS_PUBLIC : IssueService.valid(Lookups.VIEW_STATE, f.viewState(), "viewState");
        if (vs == Lookups.VS_PRIVATE && lvl < Lookups.DEVELOPER) throw denied();
        n.setViewState(vs);
        n.setNoteType(f.noteType() == null ? 0 : IssueService.valid(Lookups.NOTE_TYPE, f.noteType(), "noteType"));
        applyTime(n, f.timeMinutes(), lvl);
        notes.save(n);
        history.log(me, i, "", null, n.getId(), Lookups.H_NOTE_ADDED);
        i.setLastUpdated(Instant.now());
        notifier.noteAdded(me, i, n);
        return noteView(n, true, true);
    }

    public NoteView updateNote(User me, Long noteId, NoteForm f) {
        Note n = notes.findById(noteId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Note not found"));
        Issue i = issueService.getVisible(me, n.getIssue().getId());
        int lvl = access.levelIn(me, i.getProject());
        if (!canEditNote(me, lvl, n)) throw denied();
        n.setText(f.text());
        if (f.timeMinutes() != null) applyTime(n, f.timeMinutes(), lvl);
        if (f.viewState() != null) {
            int vs = IssueService.valid(Lookups.VIEW_STATE, f.viewState(), "viewState");
            if (vs == Lookups.VS_PRIVATE && lvl < Lookups.DEVELOPER) throw denied();
            n.setViewState(vs);
        }
        n.setLastModified(Instant.now());
        history.log(me, i, "", null, n.getId(), Lookups.H_NOTE_UPDATED);
        return noteView(n, true, lvl >= Lookups.DEVELOPER);
    }

    public void deleteNote(User me, Long noteId) {
        Note n = notes.findById(noteId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Note not found"));
        Issue i = issueService.getVisible(me, n.getIssue().getId());
        if (!canEditNote(me, access.levelIn(me, i.getProject()), n)) throw denied();
        notes.delete(n);
        history.log(me, i, "", n.getId(), null, Lookups.H_NOTE_UPDATED);
    }

    private boolean canEditNote(User me, int lvl, Note n) {
        return lvl >= Lookups.DEVELOPER || (lvl >= Lookups.REPORTER && n.getReporter().getId().equals(me.getId()));
    }

    /** time tracking: only developers (Mantis time_tracking_edit_threshold) and only when enabled; 0 = no time */
    private void applyTime(Note n, Integer minutes, int lvl) {
        if (minutes == null || (minutes == 0 && n.getTimeTracking() == 0)) return;
        if (!trackTime) throw bad("Time tracking is disabled");
        if (lvl < Lookups.DEVELOPER) throw denied();
        if (minutes < 0 || minutes > 24 * 60 * 31) throw bad("Time must be between 0 and 31 days");
        n.setTimeTracking(minutes);
        if (minutes > 0) n.setNoteType(2);                       // TIME_TRACKING note
        else if (n.getNoteType() == 2) n.setNoteType(0);
    }

    private NoteView noteView(Note n, boolean canEdit, boolean showTime) {
        return new NoteView(n.getId(), Mapper.ref(n.getReporter()), n.getText(), n.getViewState(), n.getNoteType(),
                showTime && trackTime ? n.getTimeTracking() : 0,
                n.getDateSubmitted(), n.getLastModified(), canEdit);
    }

    // ================================================================== attachments
    @Transactional(readOnly = true)
    public List<AttachmentView> listAttachments(User me, Long issueId) {
        Issue i = issueService.getVisible(me, issueId);
        int lvl = access.levelIn(me, i.getProject());
        List<AttachmentView> out = new ArrayList<>();
        for (AttachmentRepository.Meta m : attachments.findMeta(issueId)) {
            boolean can = lvl >= Lookups.DEVELOPER || (lvl >= Lookups.REPORTER && m.getUserId() != null && m.getUserId().equals(me.getId()));
            out.add(new AttachmentView(m.getId(), m.getTitle(), m.getFilename(), m.getFilesize(), m.getFileType(),
                    m.getDateAdded(), can));
        }
        return out;
    }

    public AttachmentView addAttachment(User me, Long issueId, MultipartFile file) throws IOException {
        Issue i = issueService.getVisible(me, issueId);
        int lvl = access.require(me, i.getProject(), Lookups.REPORTER);
        if (i.getStatus() >= Lookups.RESOLVED && lvl < Lookups.MANAGER)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Issue is read-only (resolved/closed)");
        if (file.isEmpty()) throw bad("Empty file");
        if (file.getSize() > api.getAttachment().getMaxSizeBytes())
            throw bad("File exceeds the configured attachment limit");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("file")
                .replaceAll("[\\\\/]", "_").replaceAll("[\\p{Cntrl}]", "");
        if (name.length() > 250) name = name.substring(name.length() - 250);
        Attachment a = new Attachment();
        a.setIssue(i); a.setUser(me); a.setFilename(name); a.setTitle(name);
        a.setFilesize(file.getSize());
        a.setFileType(file.getContentType() == null ? "application/octet-stream" : file.getContentType());
        a.setContent(file.getBytes());
        attachments.save(a);
        history.log(me, i, "", null, name, Lookups.H_FILE_ADDED);
        i.setLastUpdated(Instant.now());
        return new AttachmentView(a.getId(), a.getTitle(), a.getFilename(), a.getFilesize(), a.getFileType(), a.getDateAdded(), true);
    }

    @Transactional(readOnly = true)
    public Attachment downloadAttachment(User me, Long attachmentId) {
        Attachment a = attachments.findById(attachmentId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found"));
        issueService.getVisible(me, a.getIssue().getId());           // enforces project + private-issue rules
        return a;
    }

    public void deleteAttachment(User me, Long attachmentId) {
        Attachment a = attachments.findById(attachmentId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found"));
        Issue i = issueService.getVisible(me, a.getIssue().getId());
        int lvl = access.levelIn(me, i.getProject());
        boolean own = a.getUser() != null && a.getUser().getId().equals(me.getId());
        if (!(lvl >= Lookups.DEVELOPER || (lvl >= Lookups.REPORTER && own))) throw denied();
        attachments.delete(a);
        history.log(me, i, "", a.getFilename(), null, Lookups.H_FILE_DELETED);
    }

    // ================================================================== monitors
    public void monitor(User me, Long issueId, Long userId) {
        Issue i = issueService.getVisible(me, issueId);
        int lvl = access.require(me, i.getProject(), Lookups.REPORTER);
        User target = me;
        if (userId != null && !userId.equals(me.getId())) {
            if (lvl < Lookups.DEVELOPER) throw denied();
            target = users.findById(userId).orElseThrow(() -> bad("User not found"));
            if (access.levelIn(target, i.getProject()) < Lookups.VIEWER) throw bad("User has no access to this project");
        }
        if (monitors.findByIssueIdAndUserId(issueId, target.getId()).isPresent()) return;
        IssueMonitor m = new IssueMonitor();
        m.setIssue(i); m.setUser(target);
        monitors.save(m);
        history.log(me, i, "", null, target.getUsername(), Lookups.H_MONITOR);
    }

    public void unmonitor(User me, Long issueId, Long userId) {
        Issue i = issueService.getVisible(me, issueId);
        int lvl = access.levelIn(me, i.getProject());
        Long uid = userId == null ? me.getId() : userId;
        if (!uid.equals(me.getId()) && lvl < Lookups.DEVELOPER) throw denied();
        monitors.findByIssueIdAndUserId(issueId, uid).ifPresent(m -> {
            monitors.delete(m);
            history.log(me, i, "", m.getUser().getUsername(), null, Lookups.H_UNMONITOR);
        });
    }

    // ================================================================== relationships
    @Transactional(readOnly = true)
    public List<RelationshipView> listRelationships(User me, Long issueId) {
        issueService.getVisible(me, issueId);
        List<RelationshipView> out = new ArrayList<>();
        for (IssueRelationship r : relationships.findAllFor(issueId)) {
            boolean src = r.getSource().getId().equals(issueId);
            Issue other = src ? r.getDestination() : r.getSource();
            if (!issueService.canSee(me, other)) continue;                // do not leak private issues
            int shown = src ? r.getRelationshipType() : inverse(r.getRelationshipType());
            out.add(new RelationshipView(r.getId(), shown, Lookups.label(Lookups.RELATIONSHIP, shown),
                    other.getId(), other.getSummary(), other.getStatus()));
        }
        return out;
    }

    /** type: 0 duplicate of, 1 related to, 2 parent of, 3 child of, 4 has duplicate (relative to this issue) */
    public RelationshipView addRelationship(User me, Long issueId, RelationshipForm f) {
        Issue a = issueService.getVisible(me, issueId);
        access.require(me, a.getProject(), Lookups.UPDATER);
        if (!Lookups.RELATIONSHIP.containsKey(f.type())) throw bad("Invalid relationship type");
        if (f.otherIssueId().equals(issueId)) throw bad("An issue cannot be related to itself");
        Issue b = issueService.getVisible(me, f.otherIssueId());
        if (relationships.existsBetween(a.getId(), b.getId())) throw bad("These issues are already related");
        IssueRelationship r = new IssueRelationship();
        int t = f.type();
        if (t == 3 || t == 4) {                       // store only 0/1/2, flipping direction
            r.setSource(b); r.setDestination(a); r.setRelationshipType(t == 3 ? 2 : 0);
        } else {
            r.setSource(a); r.setDestination(b); r.setRelationshipType(t);
        }
        relationships.save(r);
        history.log(me, a, "", null, f.otherIssueId() + ":" + Lookups.label(Lookups.RELATIONSHIP, t), Lookups.H_REL_ADDED);
        history.log(me, b, "", null, a.getId() + ":" + Lookups.label(Lookups.RELATIONSHIP, inverse(t)), Lookups.H_REL_ADDED);
        return new RelationshipView(r.getId(), t, Lookups.label(Lookups.RELATIONSHIP, t), b.getId(), b.getSummary(), b.getStatus());
    }

    public void deleteRelationship(User me, Long relId) {
        IssueRelationship r = relationships.findById(relId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Relationship not found"));
        issueService.getVisible(me, r.getSource().getId());
        access.require(me, r.getSource().getProject(), Lookups.UPDATER);
        relationships.delete(r);
        history.log(me, r.getSource(), "", r.getDestination().getId(), null, Lookups.H_REL_DELETED);
        history.log(me, r.getDestination(), "", r.getSource().getId(), null, Lookups.H_REL_DELETED);
    }

    private static int inverse(int t) {
        return switch (t) { case 0 -> 4; case 2 -> 3; case 3 -> 2; case 4 -> 0; default -> 1; };
    }

    // ================================================================== history (human readable)
    @Transactional(readOnly = true)
    public List<HistoryView> history(User me, Long issueId) {
        issueService.getVisible(me, issueId);
        Map<Long, String> userNames = new HashMap<>();
        Map<Long, String> categoryNames = new HashMap<>();
        Map<Long, String> projectNames = new HashMap<>();
        List<HistoryView> out = new ArrayList<>();
        for (IssueHistory h : historyRepo.findByIssueIdOrderByDateModifiedAscIdAsc(issueId)) {
            out.add(new HistoryView(h.getId(), Mapper.ref(h.getUser()), h.getFieldName(),
                    display(h.getFieldName(), h.getOldValue(), userNames, categoryNames, projectNames),
                    display(h.getFieldName(), h.getNewValue(), userNames, categoryNames, projectNames),
                    h.getType(), h.getDateModified()));
        }
        return out;
    }

    private String display(String field, String v, Map<Long, String> userNames, Map<Long, String> catNames,
                           Map<Long, String> projectNames) {
        if (v == null || field == null) return v;
        try {
            switch (field) {
                case "status": return Lookups.label(Lookups.STATUS, Integer.parseInt(v));
                case "resolution": return Lookups.label(Lookups.RESOLUTION, Integer.parseInt(v));
                case "priority": return Lookups.label(Lookups.PRIORITY, Integer.parseInt(v));
                case "severity": return Lookups.label(Lookups.SEVERITY, Integer.parseInt(v));
                case "reproducibility": return Lookups.label(Lookups.REPRODUCIBILITY, Integer.parseInt(v));
                case "projection": return Lookups.label(Lookups.PROJECTION, Integer.parseInt(v));
                case "eta": return Lookups.label(Lookups.ETA, Integer.parseInt(v));
                case "view_state": return Lookups.label(Lookups.VIEW_STATE, Integer.parseInt(v));
                case "handler_id":
                    return userNames.computeIfAbsent(Long.parseLong(v),
                            id -> users.findById(id).map(User::getUsername).orElse("#" + id));
                case "project_id":
                    return projectNames.computeIfAbsent(Long.parseLong(v),
                            id -> projects.findById(id).map(Project::getName).orElse("#" + id));
                case "category_id":
                    return catNames.computeIfAbsent(Long.parseLong(v),
                            id -> categories.findById(id).map(Category::getName).orElse("#" + id));
                default: return v;
            }
        } catch (NumberFormatException e) {
            return v;
        }
    }
}
