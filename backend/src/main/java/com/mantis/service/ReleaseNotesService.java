package com.mantis.service;

import com.mantis.domain.*;
import com.mantis.repo.ProjectRepository;
import com.mantis.repo.VersionRepository;
import com.mantis.service.ReleaseNotes.IssueInfo;
import com.mantis.service.ReleaseNotes.VersionInfo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Roadmap and changelog pages. Only issues the user may see are listed (private issues / projects are respected because the
 * normal search rules are used); the grouping itself is plain Java in {@link ReleaseNotes}.
 * Selecting a project includes its sub-projects. At most 5000 issues are read; {@code truncated} tells when there are more.
 */
@Service
@Transactional(readOnly = true)
public class ReleaseNotesService {
    static final int MAX_ISSUES = 5000;

    private final IssueService issues;
    private final AccessService access;
    private final VersionRepository versions;
    private final ProjectRepository projects;

    public ReleaseNotesService(IssueService issues, AccessService access, VersionRepository versions, ProjectRepository projects) {
        this.issues = issues; this.access = access; this.versions = versions; this.projects = projects;
    }

    public ReleaseNotes.Result roadmap(User me, Long projectId) { return build(me, projectId, true); }

    public ReleaseNotes.Result changelog(User me, Long projectId) { return build(me, projectId, false); }

    private ReleaseNotes.Result build(User me, Long projectId, boolean roadmap) {
        Map<Long, Integer> levels = access.levels(me);
        if (projectId != null && !levels.containsKey(projectId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        Set<Long> scope = new LinkedHashSet<>(projectId == null ? levels.keySet() : access.subtreeIds(projectId));
        scope.retainAll(levels.keySet());

        Map<Long, Project> byId = projects.findAllById(scope).stream().collect(Collectors.toMap(Project::getId, Function.identity()));
        List<VersionInfo> versionInfos = new ArrayList<>();
        for (Long pid : scope) {
            Project p = byId.get(pid);
            if (p == null) continue;
            for (ProjectVersion v : versions.findByProjectIdOrderByDateOrderDesc(pid))
                versionInfos.add(new VersionInfo(pid, p.getName(), v.getId(), v.getVersion(), v.getDescription(),
                        v.getDateOrder(), v.isReleased(), v.isObsolete()));
        }

        Specification<Issue> extra = roadmap
                ? (root, q, cb) -> cb.and(cb.isNotNull(root.get("targetVersion")), cb.notEqual(root.get("targetVersion"), ""))
                : (root, q, cb) -> cb.and(cb.isNotNull(root.get("fixedInVersion")), cb.notEqual(root.get("fixedInVersion"), ""),
                        cb.greaterThanOrEqualTo(root.<Integer>get("status"), Lookups.RESOLVED));
        Page<Issue> page = issues.search(me, new IssueService.Filter(projectId, null, null, null, null, null, null, null, false, null),
                extra, PageRequest.of(0, MAX_ISSUES, Sort.by("id")));

        List<IssueInfo> infos = new ArrayList<>();
        for (Issue i : page.getContent())
            infos.add(new IssueInfo(i.getId(), i.getProject().getId(), i.getSummary(), i.getStatus(), i.getResolution(), i.getPriority(),
                    i.getHandler() == null ? null : i.getHandler().getUsername(),
                    i.getCategory() == null ? null : i.getCategory().getName(), i.getTargetVersion(), i.getFixedInVersion()));
        return new ReleaseNotes.Result(roadmap ? ReleaseNotes.roadmap(versionInfos, infos) : ReleaseNotes.changelog(versionInfos, infos),
                page.hasNext());
    }
}
