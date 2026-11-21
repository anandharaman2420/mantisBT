package com.mantis.service;

import com.mantis.domain.*;
import com.mantis.repo.ProjectRepository;
import com.mantis.repo.ProjectUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.LinkedHashMap;
import java.util.Map;
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

    public int levelIn(User u, Project p) {
        if (isAdmin(u)) return u.getAccessLevel();
        if (!p.isEnabled()) return -1;
        return compute(u, p, projectUsers.findByProjectIdAndUserId(p.getId(), u.getId()).orElse(null));
    }

    private int compute(User u, Project p, ProjectUser override) {
        if (isAdmin(u)) return u.getAccessLevel();
        if (!p.isEnabled()) return -1;
        if (override != null) return override.getAccessLevel();
        if (p.getViewState() == Lookups.VS_PRIVATE) return -1;
        return u.getAccessLevel();
    }

    /** projectId -> access level, for every project the user may at least view (one query for overrides). */
    public Map<Long, Integer> levels(User u) {
        Map<Long, ProjectUser> overrides = projectUsers.findByUserId(u.getId()).stream()
                .collect(Collectors.toMap(pu -> pu.getProject().getId(), Function.identity(), (a, b) -> a));
        Map<Long, Integer> out = new LinkedHashMap<>();
        for (Project p : projects.findAllByOrderByName()) {
            int l = compute(u, p, overrides.get(p.getId()));
            if (l >= Lookups.VIEWER) out.put(p.getId(), l);
        }
        return out;
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
