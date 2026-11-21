package com.mantis.web;

import com.mantis.domain.*;
import com.mantis.dto.Dtos.*;
import com.mantis.repo.*;
import com.mantis.security.CurrentUser;
import com.mantis.service.AccessService;
import com.mantis.service.Mapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** project_api, category_api, version_api, manage_proj_*.php, manage_user_proj */
@RestController
@RequestMapping("/api/projects")
@Transactional
public class ProjectController {
    private final ProjectRepository projects;
    private final CategoryRepository categories;
    private final VersionRepository versions;
    private final ProjectUserRepository members;
    private final UserRepository users;
    private final IssueRepository issues;
    private final NewsRepository news;
    private final CurrentUser current;
    private final AccessService access;

    public ProjectController(ProjectRepository projects, CategoryRepository categories, VersionRepository versions,
                             ProjectUserRepository members, UserRepository users, IssueRepository issues,
                             NewsRepository news, CurrentUser current, AccessService access) {
        this.projects = projects; this.categories = categories; this.versions = versions; this.members = members;
        this.users = users; this.issues = issues; this.news = news; this.current = current; this.access = access;
    }

    private ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }

    // ------------------------------------------------------------ projects
    @GetMapping
    public List<ProjectView> list() {
        User me = current.get();
        Map<Long, Integer> levels = access.levels(me);
        return projects.findAllByOrderByName().stream().filter(p -> levels.containsKey(p.getId()))
                .map(p -> Mapper.project(p, levels.get(p.getId()))).toList();
    }

    @GetMapping("/{id}")
    public ProjectView get(@PathVariable Long id) {
        User me = current.get();
        Project p = access.projectOrThrow(id);
        return Mapper.project(p, access.require(me, p, Lookups.VIEWER));
    }

    @PostMapping
    public ProjectView create(@Valid @RequestBody ProjectForm f) {
        User me = current.get();
        access.requireAdmin(me);
        if (projects.existsByName(f.name().trim())) throw new ResponseStatusException(HttpStatus.CONFLICT, "A project with this name already exists");
        Project p = new Project();
        apply(p, f);
        p.setName(f.name().trim());
        projects.save(p);
        Category c = new Category();
        c.setProject(p); c.setName("General");
        categories.save(c);
        return Mapper.project(p, me.getAccessLevel());
    }

    @PutMapping("/{id}")
    public ProjectView update(@PathVariable Long id, @Valid @RequestBody ProjectForm f) {
        User me = current.get();
        Project p = access.projectOrThrow(id);
        int lvl = access.require(me, p, Lookups.MANAGER);
        if (!p.getName().equals(f.name().trim()) && projects.existsByName(f.name().trim()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A project with this name already exists");
        apply(p, f);
        p.setName(f.name().trim());
        return Mapper.project(p, lvl);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        access.requireAdmin(current.get());
        Project p = access.projectOrThrow(id);
        if (issues.countByProjectId(id) > 0) throw bad("Project still has issues. Disable it instead of deleting.");
        news.findAll().stream().filter(n -> n.getProject() != null && n.getProject().getId().equals(id)).forEach(news::delete);
        members.deleteByProjectId(id);
        versions.deleteByProjectId(id);
        categories.deleteByProjectId(id);
        projects.delete(p);
    }

    private void apply(Project p, ProjectForm f) {
        if (f.status() != null) {
            if (!Lookups.PROJECT_STATUS.containsKey(f.status())) throw bad("Invalid project status");
            p.setStatus(f.status());
        }
        if (f.enabled() != null) p.setEnabled(f.enabled());
        if (f.viewState() != null) {
            if (!Lookups.VIEW_STATE.containsKey(f.viewState())) throw bad("Invalid view state");
            p.setViewState(f.viewState());
        }
        if (f.description() != null) p.setDescription(f.description());
    }

    // ------------------------------------------------------------ categories
    @GetMapping("/{id}/categories")
    public List<CategoryView> categories(@PathVariable Long id) {
        Project p = access.projectOrThrow(id);
        access.require(current.get(), p, Lookups.VIEWER);
        return categories.findByProjectIdOrderByName(id).stream().map(this::categoryView).toList();
    }

    @PostMapping("/{id}/categories")
    public CategoryView addCategory(@PathVariable Long id, @Valid @RequestBody CategoryForm f) {
        Project p = access.projectOrThrow(id);
        access.require(current.get(), p, Lookups.MANAGER);
        if (categories.existsByProjectIdAndName(id, f.name().trim()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Category already exists");
        Category c = new Category();
        c.setProject(p);
        c.setName(f.name().trim());
        c.setDefaultHandler(handler(p, f.defaultHandlerId()));
        return categoryView(categories.save(c));
    }

    @PutMapping("/categories/{cid}")
    public CategoryView updateCategory(@PathVariable Long cid, @Valid @RequestBody CategoryForm f) {
        Category c = categories.findById(cid).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));
        access.require(current.get(), c.getProject(), Lookups.MANAGER);
        c.setName(f.name().trim());
        c.setDefaultHandler(handler(c.getProject(), f.defaultHandlerId()));
        return categoryView(c);
    }

    @DeleteMapping("/categories/{cid}")
    public void deleteCategory(@PathVariable Long cid) {
        Category c = categories.findById(cid).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));
        access.require(current.get(), c.getProject(), Lookups.MANAGER);
        if (issues.countByCategoryId(cid) > 0) throw bad("Category is still used by issues");
        categories.delete(c);
    }

    private User handler(Project p, Long userId) {
        if (userId == null) return null;
        User u = users.findById(userId).orElseThrow(() -> bad("User not found"));
        if (access.levelIn(u, p) < Lookups.DEVELOPER) throw bad("Default assignee must be a developer of the project");
        return u;
    }

    private CategoryView categoryView(Category c) {
        User h = c.getDefaultHandler();
        return new CategoryView(c.getId(), c.getName(), h == null ? null : h.getId(), h == null ? null : h.getUsername());
    }

    // ------------------------------------------------------------ versions
    @GetMapping("/{id}/versions")
    public List<VersionView> versions(@PathVariable Long id) {
        Project p = access.projectOrThrow(id);
        access.require(current.get(), p, Lookups.VIEWER);
        return versions.findByProjectIdOrderByDateOrderDesc(id).stream().map(this::versionView).toList();
    }

    @PostMapping("/{id}/versions")
    public VersionView addVersion(@PathVariable Long id, @Valid @RequestBody VersionForm f) {
        Project p = access.projectOrThrow(id);
        access.require(current.get(), p, Lookups.MANAGER);
        ProjectVersion v = new ProjectVersion();
        v.setProject(p);
        applyVersion(v, f);
        return versionView(versions.save(v));
    }

    @PutMapping("/versions/{vid}")
    public VersionView updateVersion(@PathVariable Long vid, @Valid @RequestBody VersionForm f) {
        ProjectVersion v = versions.findById(vid).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Version not found"));
        access.require(current.get(), v.getProject(), Lookups.MANAGER);
        applyVersion(v, f);
        return versionView(v);
    }

    @DeleteMapping("/versions/{vid}")
    public void deleteVersion(@PathVariable Long vid) {
        ProjectVersion v = versions.findById(vid).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Version not found"));
        access.require(current.get(), v.getProject(), Lookups.MANAGER);
        versions.delete(v);
    }

    private void applyVersion(ProjectVersion v, VersionForm f) {
        v.setVersion(f.version().trim());
        if (f.description() != null) v.setDescription(f.description());
        if (f.released() != null) v.setReleased(f.released());
        if (f.obsolete() != null) v.setObsolete(f.obsolete());
        if (v.getDateOrder() == null) v.setDateOrder(Instant.now());
    }

    private VersionView versionView(ProjectVersion v) {
        return new VersionView(v.getId(), v.getVersion(), v.getDescription(), v.isReleased(), v.isObsolete(), v.getDateOrder());
    }

    // ------------------------------------------------------------ members (per-project access levels)
    @GetMapping("/{id}/members")
    public List<MemberView> members(@PathVariable Long id) {
        Project p = access.projectOrThrow(id);
        access.require(current.get(), p, Lookups.MANAGER);
        return members.findByProjectId(id).stream()
                .map(m -> new MemberView(m.getUser().getId(), m.getUser().getUsername(), m.getUser().getRealname(), m.getAccessLevel()))
                .sorted((a, b) -> a.username().compareToIgnoreCase(b.username())).toList();
    }

    @PutMapping("/{id}/members")
    public MemberView setMember(@PathVariable Long id, @Valid @RequestBody MemberForm f) {
        User me = current.get();
        Project p = access.projectOrThrow(id);
        int lvl = access.require(me, p, Lookups.MANAGER);
        if (!Lookups.ACCESS.containsKey(f.accessLevel()) || f.accessLevel() >= Lookups.ADMINISTRATOR)
            throw bad("Invalid access level");
        if (f.accessLevel() > lvl) throw bad("You cannot grant a higher level than your own");
        User u = users.findById(f.userId()).orElseThrow(() -> bad("User not found"));
        ProjectUser pu = members.findByProjectIdAndUserId(id, u.getId()).orElseGet(() -> {
            ProjectUser n = new ProjectUser(); n.setProject(p); n.setUser(u); return n;
        });
        pu.setAccessLevel(f.accessLevel());
        members.save(pu);
        return new MemberView(u.getId(), u.getUsername(), u.getRealname(), pu.getAccessLevel());
    }

    @DeleteMapping("/{id}/members/{userId}")
    public void removeMember(@PathVariable Long id, @PathVariable Long userId) {
        Project p = access.projectOrThrow(id);
        access.require(current.get(), p, Lookups.MANAGER);
        members.findByProjectIdAndUserId(id, userId).ifPresent(members::delete);
    }
}
