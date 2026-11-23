package com.mantis.service;

import com.mantis.domain.*;
import com.mantis.repo.ProjectRepository;
import com.mantis.repo.ProjectUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Port of Mantis access_api.php.
 * Effective access level in a project = per-project override (project_user_list) if present,
 * otherwise the global level for public projects. Private projects are invisible without membership.
 */
@Service
public class AccessService {
    private final ProjectRepository projects;
    private final ProjectUserRepository projectUsers;

    public AccessService(ProjectRepository projects, ProjectUserRepository projectUsers) {
        this.projects = projects;
        this.projectUsers = projectUsers;
    }

    public boolean isAdmin(User u) { return u.getAccessLevel() >= Lookups.ADMINISTRATOR; }

    /**
     * Effective access level of a user in a project:
     *  1. administrators: their global level everywhere
     *  2. a per-project entry (project_user_list)
     *  3. sub-project that inherits from its parent: the level the user has in the parent, if he can see it
     *  4. private project: no access, otherwise the global level
     */
    public int levelIn(User u, Project p) {
        if (isAdmin(u)) return u.getAccessLevel();
        return levelOf(u, p, new HashSet<>());
    }

    private int levelOf(User u, Project p, Set<Long> seen) {
        if (!p.isEnabled() || !seen.add(p.getId())) return -1;
        ProjectUser o = projectUsers.findByProjectIdAndUserId(p.getId(), u.getId()).orElse(null);
        if (o != null) return o.getAccessLevel();
        if (p.getParent() != null && p.isInheritParent()) {
            int pl = levelOf(u, p.getParent(), seen);
            if (pl >= Lookups.VIEWER) return pl;
        }
        return p.getViewState() == Lookups.VS_PRIVATE ? -1 : u.getAccessLevel();
    }

    /** projectId -> access level, for every project the user may at least view (one query for overrides). */
    public Map<Long, Integer> levels(User u) {
        Map<Long, ProjectUser> overrides = projectUsers.findByUserId(u.getId()).stream()
                .collect(Collectors.toMap(pu -> pu.getProject().getId(), Function.identity(), (a, b) -> a));
        List<Project> all = projects.findAllByOrderByName();
        Map<Long, Project> byId = all.stream().collect(Collectors.toMap(Project::getId, Function.identity()));
        Map<Long, Integer> memo = new HashMap<>();
        Map<Long, Integer> out = new LinkedHashMap<>();
        for (Project p : all) {
            int l = levelMemo(u, p, byId, overrides, memo, new HashSet<>());
            if (l >= Lookups.VIEWER) out.put(p.getId(), l);
        }
        return out;
    }

    private int levelMemo(User u, Project p, Map<Long, Project> byId, Map<Long, ProjectUser> ov,
                          Map<Long, Integer> memo, Set<Long> seen) {
        Integer known = memo.get(p.getId());
        if (known != null) return known;
        int l;
        if (isAdmin(u)) {
            l = u.getAccessLevel();
        } else if (!p.isEnabled() || !seen.add(p.getId())) {
            l = -1;
        } else if (ov.containsKey(p.getId())) {
            l = ov.get(p.getId()).getAccessLevel();
        } else {
            l = Integer.MIN_VALUE;
            Long parentId = p.getParent() == null ? null : p.getParent().getId();
            if (parentId != null && p.isInheritParent() && byId.containsKey(parentId)) {
                int pl = levelMemo(u, byId.get(parentId), byId, ov, memo, seen);
                if (pl >= Lookups.VIEWER) l = pl;
            }
            if (l == Integer.MIN_VALUE) l = p.getViewState() == Lookups.VS_PRIVATE ? -1 : u.getAccessLevel();
        }
        memo.put(p.getId(), l);
        return l;
    }

    /** the project itself and all its sub-projects (any depth) */
    public Set<Long> subtreeIds(Long rootId) {
        Map<Long, List<Long>> children = new HashMap<>();
        for (Project p : projects.findAll())
            if (p.getParent() != null) children.computeIfAbsent(p.getParent().getId(), k -> new ArrayList<>()).add(p.getId());
        Set<Long> out = new LinkedHashSet<>();
        Deque<Long> todo = new ArrayDeque<>(List.of(rootId));
        while (!todo.isEmpty()) {
            Long id = todo.pop();
            if (out.add(id)) todo.addAll(children.getOrDefault(id, List.of()));
        }
        return out;
    }

    /** parents (nearest first) whose categories / versions / custom fields this project inherits */
    public List<Project> inheritedAncestors(Project p) {
        List<Project> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>(List.of(p.getId()));
        Project cur = p;
        while (cur.getParent() != null && cur.isInheritParent()) {
            Project par = cur.getParent();
            if (!seen.add(par.getId())) break;                 // cycle safety
            out.add(par);
            cur = par;
        }
        return out;
    }

    /** project visible + (private issues only for reporter, handler or developer+) */
    public boolean canSee(User me, Issue i) {
        int lvl = levelIn(me, i.getProject());
        if (lvl < Lookups.VIEWER) return false;
        if (i.getViewState() == Lookups.VS_PRIVATE) {
            return lvl >= Lookups.DEVELOPER || i.getReporter().getId().equals(me.getId())
                    || (i.getHandler() != null && i.getHandler().getId().equals(me.getId()));
        }
        return true;
    }

    public Project projectOrThrow(Long id) {
        return projects.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    }

    /** Throws 404 when the user cannot even see the project, 403 when level is too low. */
    public int require(User u, Project p, int min) {
        int l = levelIn(u, p);
        if (l < Lookups.VIEWER) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        if (l < min) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        return l;
    }

    public void requireAdmin(User u) {
        if (!isAdmin(u)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access required");
    }
}
