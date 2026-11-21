package com.mantis.web;

import com.mantis.domain.Attachment;
import com.mantis.domain.Issue;
import com.mantis.domain.User;
import com.mantis.dto.Dtos.*;
import com.mantis.security.CurrentUser;
import com.mantis.service.IssueExtrasService;
import com.mantis.service.IssueService;
import com.mantis.service.Mapper;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** view_all_bug_page, bug_report, bug_update, bug_view, bugnote_*, bug_file_*, bug_monitor_*, bug_relationship_* */
@RestController
@RequestMapping("/api")
@Transactional
public class IssueController {
    private static final Set<String> SORTABLE = Set.of("id", "lastUpdated", "dateSubmitted", "priority", "severity", "status", "summary");

    private final IssueService issues;
    private final IssueExtrasService extras;
    private final CurrentUser current;

    public IssueController(IssueService issues, IssueExtrasService extras, CurrentUser current) {
        this.issues = issues; this.extras = extras; this.current = current;
    }

    // ------------------------------------------------------------ list / CRUD
    @GetMapping("/issues")
    public PageResult<IssueSummary> search(@RequestParam(required = false) Long projectId,
                                           @RequestParam(required = false) List<Integer> status,
                                           @RequestParam(required = false) Long handlerId,
                                           @RequestParam(required = false) Long reporterId,
                                           @RequestParam(required = false) Integer priority,
                                           @RequestParam(required = false) Integer severity,
                                           @RequestParam(required = false) Long categoryId,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(required = false) String tag,
                                           @RequestParam(defaultValue = "true") boolean hideClosed,
                                           @RequestParam(defaultValue = "lastUpdated") String sortBy,
                                           @RequestParam(defaultValue = "desc") String dir,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "25") int size) {
        User me = current.get();
        String sortField = SORTABLE.contains(sortBy) ? sortBy : "lastUpdated";
        Sort sort = Sort.by("asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC, sortField)
                .and(Sort.by(Sort.Direction.DESC, "id"));
        PageRequest pg = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), sort);
        Page<Issue> result = issues.search(me, new IssueService.Filter(projectId, status, handlerId, reporterId,
                priority, severity, categoryId, q, hideClosed, tag), pg);
        return new PageResult<>(result.getContent().stream().map(Mapper::summary).toList(),
                result.getTotalElements(), result.getNumber(), result.getSize());
    }

    @GetMapping("/issues/{id}")
    public IssueView get(@PathVariable Long id) {
        User me = current.get();
        return issues.view(me, issues.getVisible(me, id));
    }

    @PostMapping("/issues")
    public IssueView create(@RequestBody IssueForm f) {
        User me = current.get();
        return issues.view(me, issues.create(me, f));
    }

    @PutMapping("/issues/{id}")
    public IssueView update(@PathVariable Long id, @RequestBody IssueForm f) {
        User me = current.get();
        return issues.view(me, issues.update(me, id, f));
    }

    @PostMapping("/issues/{id}/status")
    public IssueView changeStatus(@PathVariable Long id, @Valid @RequestBody StatusChange c) {
        User me = current.get();
        return issues.view(me, issues.changeStatus(me, id, c));
    }

    @DeleteMapping("/issues/{id}")
    public void delete(@PathVariable Long id) { issues.delete(current.get(), id); }

    @GetMapping("/summary")
    public SummaryView summary(@RequestParam(required = false) Long projectId) {
        return issues.summary(current.get(), projectId);
    }

    // ------------------------------------------------------------ history
    @GetMapping("/issues/{id}/history")
    public List<HistoryView> history(@PathVariable Long id) { return extras.history(current.get(), id); }

    // ------------------------------------------------------------ notes
    @GetMapping("/issues/{id}/notes")
    public List<NoteView> notes(@PathVariable Long id) { return extras.listNotes(current.get(), id); }

    @PostMapping("/issues/{id}/notes")
    public NoteView addNote(@PathVariable Long id, @Valid @RequestBody NoteForm f) { return extras.addNote(current.get(), id, f); }

    @PutMapping("/notes/{noteId}")
    public NoteView updateNote(@PathVariable Long noteId, @Valid @RequestBody NoteForm f) { return extras.updateNote(current.get(), noteId, f); }

    @DeleteMapping("/notes/{noteId}")
    public void deleteNote(@PathVariable Long noteId) { extras.deleteNote(current.get(), noteId); }

    // ------------------------------------------------------------ attachments
    @GetMapping("/issues/{id}/attachments")
    public List<AttachmentView> attachments(@PathVariable Long id) { return extras.listAttachments(current.get(), id); }

    @PostMapping(value = "/issues/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AttachmentView upload(@PathVariable Long id, @RequestParam("file") MultipartFile file) throws IOException {
        return extras.addAttachment(current.get(), id, file);
    }

    @GetMapping("/attachments/{attId}")
    public ResponseEntity<byte[]> download(@PathVariable Long attId) {
        Attachment a = extras.downloadAttachment(current.get(), attId);
        MediaType type;
        try { type = MediaType.parseMediaType(a.getFileType()); } catch (Exception e) { type = MediaType.APPLICATION_OCTET_STREAM; }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(a.getFilename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")      // never let the browser render uploaded content
                .contentType(type)
                .body(a.getContent());
    }

    @DeleteMapping("/attachments/{attId}")
    public void deleteAttachment(@PathVariable Long attId) { extras.deleteAttachment(current.get(), attId); }

    // ------------------------------------------------------------ monitors
    @PostMapping("/issues/{id}/monitors")
    public Map<String, Boolean> monitor(@PathVariable Long id, @RequestBody(required = false) MonitorForm f) {
        extras.monitor(current.get(), id, f == null ? null : f.userId());
        return Map.of("monitoring", true);
    }

    @DeleteMapping("/issues/{id}/monitors")
    public Map<String, Boolean> unmonitor(@PathVariable Long id, @RequestParam(required = false) Long userId) {
        extras.unmonitor(current.get(), id, userId);
        return Map.of("monitoring", false);
    }

    // ------------------------------------------------------------ relationships
    @GetMapping("/issues/{id}/relationships")
    public List<RelationshipView> relationships(@PathVariable Long id) { return extras.listRelationships(current.get(), id); }

    @PostMapping("/issues/{id}/relationships")
    public RelationshipView addRelationship(@PathVariable Long id, @Valid @RequestBody RelationshipForm f) {
        return extras.addRelationship(current.get(), id, f);
    }

    @DeleteMapping("/relationships/{relId}")
    public void deleteRelationship(@PathVariable Long relId) { extras.deleteRelationship(current.get(), relId); }
}
