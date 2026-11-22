package com.mantis.api.rest;

import com.mantis.api.rest.RestDtos.*;
import com.mantis.domain.*;
import com.mantis.repo.CategoryRepository;
import com.mantis.repo.ProjectRepository;
import com.mantis.repo.TagRepository;
import com.mantis.repo.VersionRepository;
import com.mantis.security.CurrentUser;
import com.mantis.service.AccessService;
import com.mantis.service.IssueService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Mantis REST compatible project / category / version endpoints. */
@RestController
@RequestMapping("/api/rest")
@ConditionalOnProperty(name = "app.api.rest.enabled", havingValue = "true", matchIfMissing = true)
@Transactional(readOnly = true)
public class RestProjectController {
    private final ProjectRepository projects;
    private final CategoryRepository categories;
    private final VersionRepository versions;
    private final TagRepository tags;
    private final IssueService issues;
    private final AccessService access;
    private final CurrentUser current;

    public RestProjectController(ProjectRepository projects, CategoryRepository categories,
                                 VersionRepository versions, TagRepository tags, IssueService issues,
                                 AccessService access, CurrentUser current) {
        this.projects = projects; this.categories = categories; this.versions = versions; this.tags = tags;
        this.issues = issues; this.access = access; this.current = current;
    }

    @GetMapping("/projects")
    public List<ProjectDto> list() {
        User me = current.get();
        Map<Long, Integer> levels = access.levels(me);
        return projects.findAllByOrderByName().stream()
                .filter(p -> levels.containsKey(p.getId()))
                .map(RestMapper::project).toList();
    }

    @GetMapping("/projects/{id}")
    public ProjectDto get(@PathVariable Long id) {
        User me = current.get();
        Project p = access.projectOrThrow(id);
        access.require(me, p, Lookups.VIEWER);
        return RestMapper.project(p);
    }

    @GetMapping("/projects/{id}/categories")
    public List<CategoryDto> categories(@PathVariable Long id) {
        User me = current.get();
        Project p = access.projectOrThrow(id);
        access.require(me, p, Lookups.VIEWER);
        List<CategoryDto> out = new ArrayList<>();
        categories.findByProjectIdOrderByName(id).forEach(c -> out.add(RestMapper.category(c)));
        for (Project ancestor : access.inheritedAncestors(p))
            categories.findByProjectIdOrderByName(ancestor.getId()).forEach(c -> out.add(RestMapper.category(c)));
        return out;
    }

    @GetMapping("/projects/{id}/versions")
    public List<VersionDto> versions(@PathVariable Long id) {
        User me = current.get();
        Project p = access.projectOrThrow(id);
        access.require(me, p, Lookups.VIEWER);
        List<VersionDto> out = new ArrayList<>();
        versions.findByProjectIdOrderByDateOrderDesc(id).forEach(v -> out.add(RestMapper.version(v)));
        for (Project ancestor : access.inheritedAncestors(p))
            versions.findByProjectIdOrderByDateOrderDesc(ancestor.getId()).forEach(v -> out.add(RestMapper.version(v)));
        return out;
    }

    @GetMapping("/projects/{id}/issues")
    public ResponseEntity<IssueListDto> issues(@PathVariable Long id,
                               @RequestParam(required = false) List<Integer> status,
                               @RequestParam(name = "page", defaultValue = "1") int page,
                               @RequestParam(name = "page_size", defaultValue = "50") int pageSize,
                               @RequestParam(name = "hide_closed", defaultValue = "false") boolean hideClosed) {
        User me = current.get();
        Project p = access.projectOrThrow(id);
        access.require(me, p, Lookups.VIEWER);
        int size = Math.min(Math.max(pageSize, 1), 100);
        PageRequest pg = PageRequest.of(Math.max(page - 1, 0), size, Sort.by(Sort.Direction.DESC, "lastUpdated"));
        IssueService.Filter filter = new IssueService.Filter(id, status, null, null, null, null, null, null, hideClosed, null);
        Page<com.mantis.dto.Dtos.IssueView> result = issues.searchViews(me, filter, pg);
        Map<String, TagDto> tagIndex = tagIndex();
        List<IssueDto> items = result.getContent().stream()
                .map(v -> RestMapper.issue(v, null, tagIndex)).toList();
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.getTotalElements()))
                .body(new IssueListDto(items, result.getTotalElements(), page, size));
    }

    private Map<String, TagDto> tagIndex() {
        return tags.findAllByOrderByName().stream()
                .collect(Collectors.toMap(t -> t.getName().toLowerCase(), RestMapper::tag, (a, b) -> a));
    }
}
