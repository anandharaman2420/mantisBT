package com.mantis.api.rest;

import com.mantis.api.rest.RestDtos.*;
import com.mantis.config.ApiProperties;
import com.mantis.domain.*;
import com.mantis.dto.Dtos.*;
import com.mantis.repo.TagRepository;
import com.mantis.repo.UserRepository;
import com.mantis.security.CurrentUser;
import com.mantis.service.IssueExtrasService;
import com.mantis.service.IssueService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/** Mantis REST compatible issue, note, file, tag, relationship and monitor endpoints ({@code /api/rest/issues}). */
@RestController
@RequestMapping("/api/rest")
@ConditionalOnProperty(name = "app.api.rest.enabled", havingValue = "true", matchIfMissing = true)
@Transactional
public class RestIssueController {
    private static final Set<String> SORTABLE = Set.of(
            "id", "lastUpdated", "dateSubmitted", "priority", "severity", "status", "summary");

    private final IssueService issues;
    private final IssueExtrasService extras;
    private final CurrentUser current;
    private final TagRepository tags;
    private final UserRepository users;
    private final ApiProperties api;

    public RestIssueController(IssueService issues, IssueExtrasService extras, CurrentUser current,
                               TagRepository tags, UserRepository users, ApiProperties api) {
        this.issues = issues; this.extras = extras; this.current = current; this.tags = tags;
        this.users = users; this.api = api;
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    private static ResponseStatusException notFound(String m) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, m);
    }

    // ------------------------------------------------------------------ issues

    @GetMapping("/issues")
    public ResponseEntity<IssueListDto> list(@RequestParam(name = "project_id", required = false) Long projectId,
                             @RequestParam(required = false) List<Integer> status,
                             @RequestParam(name = "handler_id", required = false) Long handlerId,
                             @RequestParam(name = "reporter_id", required = false) Long reporterId,
                             @RequestParam(required = false) Integer priority,
                             @RequestParam(required = false) Integer severity,
                             @RequestParam(name = "category_id", required = false) Long categoryId,
                             @RequestParam(required = false) String search,
                             @RequestParam(required = false) String tag,
                             @RequestParam(name = "hide_closed", defaultValue = "false") boolean hideClosed,
                             @RequestParam(name = "page", defaultValue = "1") int page,
                             @RequestParam(name = "page_size", defaultValue = "50") int pageSize,
                             @RequestParam(required = false) String sort,
                             @RequestParam(defaultValue = "desc") String dir) {
        User me = current.get();
        int size = Math.min(Math.max(pageSize, 1), 100);
        String field = sort != null && SORTABLE.contains(sort) ? sort : "lastUpdated";
        Sort sorting = Sort.by("asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC, field)
                .and(Sort.by(Sort.Direction.DESC, "id"));
        PageRequest pg = PageRequest.of(Math.max(page - 1, 0), size, sorting);
        IssueService.Filter filter = new IssueService.Filter(projectId, status, handlerId, reporterId, priority,
                severity, categoryId, search, hideClosed, tag);
        Page<IssueView> result = issues.searchViews(me, filter, pg);
        Map<String, TagDto> index = tagIndex();
        List<IssueDto> items = result.getContent().stream()
                .map(v -> RestMapper.issue(v, null, index)).toList();
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.getTotalElements()))
                .body(new IssueListDto(items, result.getTotalElements(), page, size));
    }

    @GetMapping("/issues/{id}")
    public IssueDto get(@PathVariable Long id) {
        User me = current.get();
        IssueView view = issues.viewById(me, id);
        List<NoteDto> notes = extras.listNotes(me, id).stream().map(RestMapper::note).toList();
        return RestMapper.issue(view, notes, tagIndex());
    }

    @PostMapping("/issues")
    @ResponseStatus(HttpStatus.CREATED)
    public IssueDto create(@RequestBody IssueInputDto in) {
        if (in == null) throw bad("Request body is required");
        User me = current.get();
        Issue created = issues.create(me, RestMapper.toForm(in));
        return RestMapper.issue(issues.viewById(me, created.getId()), List.of(), tagIndex());
    }

    @PatchMapping("/issues/{id}")
    public IssueDto update(@PathVariable Long id, @RequestBody IssueInputDto in) {
        if (in == null) throw bad("Request body is required");
        User me = current.get();
        issues.update(me, id, RestMapper.toForm(in));
        return RestMapper.issue(issues.viewById(me, id), List.of(), tagIndex());
    }

    @DeleteMapping("/issues/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        issues.delete(current.get(), id);
    }

    // ------------------------------------------------------------------ notes

    @GetMapping("/issues/{id}/notes")
    public List<NoteDto> notes(@PathVariable Long id) {
        return extras.listNotes(current.get(), id).stream().map(RestMapper::note).toList();
    }

    @PostMapping("/issues/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public NoteDto addNote(@PathVariable Long id, @RequestBody NoteInputDto in) {
        if (in == null || in.text() == null || in.text().isBlank()) throw bad("text is required");
        NoteView n = extras.addNote(current.get(), id, RestMapper.toNoteForm(in));
        return RestMapper.note(n);
    }

    // ------------------------------------------------------------------ files

    @GetMapping("/issues/{id}/files")
    public List<FileDto> files(@PathVariable Long id) {
        return extras.listAttachments(current.get(), id).stream().map(RestMapper::file).toList();
    }

    @PostMapping(value = "/issues/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FileDto upload(@PathVariable Long id, @RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw bad("file is required");
        if (file.getSize() > api.getAttachment().getMaxSizeBytes())
            throw bad("File exceeds the configured attachment limit");
        return RestMapper.file(extras.addAttachment(current.get(), id, file));
    }

    @GetMapping("/issues/{id}/files/{fileId}")
    public ResponseEntity<byte[]> download(@PathVariable Long id, @PathVariable Long fileId) {
        User me = current.get();
        boolean belongs = extras.listAttachments(me, id).stream().anyMatch(f -> f.id().equals(fileId));
        if (!belongs) throw notFound("File not found");
        Attachment file = extras.downloadAttachment(me, fileId);
        MediaType type;
        try {
            type = file.getFileType() == null || file.getFileType().isBlank()
                    ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(file.getFileType());
        } catch (Exception e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.getFilename()).build().toString())
                .contentType(type)
                .body(file.getContent());
    }

    // ------------------------------------------------------------------ relationships

    @GetMapping("/issues/{id}/relationships")
    public List<RelationshipDto> relationships(@PathVariable Long id) {
        return extras.listRelationships(current.get(), id).stream().map(RestMapper::relationship).toList();
    }

    @PostMapping("/issues/{id}/relationships")
    @ResponseStatus(HttpStatus.CREATED)
    public RelationshipDto addRelationship(@PathVariable Long id, @RequestBody RelationshipInputDto in) {
        if (in == null || in.issue() == null || in.issue().id() == null) throw bad("issue.id is required");
        Integer type = RestMapper.enumId(in.type(), Lookups.RELATIONSHIP);
        if (type == null) throw bad("type is required");
        RelationshipView r = extras.addRelationship(current.get(), id,
                new RelationshipForm(type, in.issue().id().longValue()));
        return RestMapper.relationship(r);
    }

    @DeleteMapping("/issues/{id}/relationships/{relationshipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRelationship(@PathVariable Long id, @PathVariable Long relationshipId) {
        User me = current.get();
        boolean belongs = extras.listRelationships(me, id).stream()
                .anyMatch(r -> r.id().equals(relationshipId));
        if (!belongs) throw notFound("Relationship not found");
        extras.deleteRelationship(me, relationshipId);
    }

    // ------------------------------------------------------------------ tags

    @GetMapping("/issues/{id}/tags")
    public List<TagDto> issueTags(@PathVariable Long id) {
        User me = current.get();
        IssueView view = issues.viewById(me, id);
        Map<String, TagDto> index = tagIndex();
        return view.tags().stream()
                .map(name -> index.getOrDefault(name.toLowerCase(), new TagDto(null, name, null)))
                .toList();
    }

    @PostMapping("/issues/{id}/tags")
    @ResponseStatus(HttpStatus.CREATED)
    public TagDto addTag(@PathVariable Long id, @RequestBody TagInputDto in) {
        String name = tagName(in);
        if (name == null) throw bad("tag name is required");
        User me = current.get();
        IssueView view = issues.viewById(me, id);
        List<String> names = new ArrayList<>(view.tags());
        if (names.stream().noneMatch(n -> n.equalsIgnoreCase(name))) names.add(name);
        issues.update(me, id, RestMapper.tagsForm(names));
        Tag tag = tags.findByNameIgnoreCase(name)
                .orElseThrow(() -> notFound("Tag not found"));
        return RestMapper.tag(tag);
    }

    @DeleteMapping("/issues/{id}/tags/{tagId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeTag(@PathVariable Long id, @PathVariable Long tagId) {
        Tag tag = tags.findById(tagId).orElseThrow(() -> notFound("Tag not found"));
        User me = current.get();
        IssueView view = issues.viewById(me, id);
        List<String> names = view.tags().stream()
                .filter(n -> !n.equalsIgnoreCase(tag.getName())).toList();
        issues.update(me, id, RestMapper.tagsForm(names));
    }

    private static String tagName(TagInputDto in) {
        if (in == null) return null;
        String name = in.tag() != null && in.tag().name() != null ? in.tag().name() : in.name();
        return name == null || name.isBlank() ? null : name.trim();
    }

    // ------------------------------------------------------------------ monitors

    @GetMapping("/issues/{id}/monitors")
    public List<UserDto> monitors(@PathVariable Long id) {
        User me = current.get();
        // IssueService.view only exposes the monitor list to developer+ (same rule as the web UI).
        return issues.viewById(me, id).monitors().stream().map(RestMapper::user).toList();
    }

    @PostMapping("/issues/{id}/monitors")
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto monitor(@PathVariable Long id, @RequestBody(required = false) MonitorInputDto in) {
        User me = current.get();
        Long userId = in == null || in.user() == null || in.user().id() == null
                ? null : in.user().id().longValue();
        extras.monitor(me, id, userId);
        User target = userId == null ? me
                : users.findById(userId).orElseThrow(() -> notFound("User not found"));
        return RestMapper.user(target);
    }

    @DeleteMapping("/issues/{id}/monitors/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unmonitor(@PathVariable Long id, @PathVariable Long userId) {
        extras.unmonitor(current.get(), id, userId);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, TagDto> tagIndex() {
        return tags.findAllByOrderByName().stream()
                .collect(Collectors.toMap(t -> t.getName().toLowerCase(), RestMapper::tag, (a, b) -> a));
    }
}
