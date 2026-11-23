package com.mantis.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mantis.domain.Lookups;
import com.mantis.domain.Project;
import com.mantis.domain.SavedFilter;
import com.mantis.domain.User;
import com.mantis.dto.Dtos.*;
import com.mantis.repo.SavedFilterRepository;
import com.mantis.security.CurrentUser;
import com.mantis.service.AccessService;
import com.mantis.service.Mapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Saved filters / queries for issues (Mantis mantis_filters_table). */
@RestController
@RequestMapping("/api/filters")
@Transactional
public class SavedFilterController {
    private final SavedFilterRepository filters;
    private final CurrentUser current;
    private final AccessService access;
    private final ObjectMapper mapper;

    public SavedFilterController(SavedFilterRepository filters, CurrentUser current,
                                 AccessService access, ObjectMapper mapper) {
        this.filters = filters;
        this.current = current;
        this.access = access;
        this.mapper = mapper;
    }

    @GetMapping
    public List<SavedFilterView> list(@RequestParam(required = false) Long projectId) {
        User me = current.get();
        Map<Long, Integer> levels = access.levels(me);
        Set<Long> pids = levels.keySet();

        List<SavedFilter> list = pids.isEmpty()
                ? filters.findByUserIdOrderByNameAsc(me.getId())
                : filters.findVisible(me.getId(), pids);

        return list.stream()
                .filter(f -> projectId == null || f.getProject() == null || f.getProject().getId().equals(projectId))
                .map(this::toView)
                .toList();
    }

    @PostMapping
    public SavedFilterView create(@Valid @RequestBody SavedFilterForm form) {
        User me = current.get();
        Project p = null;
        if (form.projectId() != null) {
            p = access.projectOrThrow(form.projectId());
            access.require(me, p, Lookups.VIEWER);
        }

        boolean isPublic = Boolean.TRUE.equals(form.isPublic());
        if (isPublic && !access.isAdmin(me)) {
            int lvl = p != null ? access.levelIn(me, p) : me.getAccessLevel();
            if (lvl < Lookups.DEVELOPER) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Developer or higher required to create public filters");
            }
        }

        SavedFilter f = new SavedFilter();
        f.setUser(me);
        f.setProject(p);
        f.setName(form.name().trim());
        f.setPublic(isPublic);
        f.setFilterString(toJson(form.criteria()));

        return toView(filters.save(f));
    }

    @PutMapping("/{id}")
    public SavedFilterView update(@PathVariable Long id, @Valid @RequestBody SavedFilterForm form) {
        User me = current.get();
        SavedFilter f = filters.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Filter not found"));

        if (!f.getUser().getId().equals(me.getId()) && !access.isAdmin(me)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the author or an administrator can edit this filter");
        }

        Project p = null;
        if (form.projectId() != null) {
            p = access.projectOrThrow(form.projectId());
            access.require(me, p, Lookups.VIEWER);
        }

        boolean isPublic = Boolean.TRUE.equals(form.isPublic());
        if (isPublic && !f.isPublic() && !access.isAdmin(me)) {
            int lvl = p != null ? access.levelIn(me, p) : me.getAccessLevel();
            if (lvl < Lookups.DEVELOPER) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Developer or higher required to create public filters");
            }
        }

        f.setName(form.name().trim());
        f.setProject(p);
        f.setPublic(isPublic);
        if (form.criteria() != null) {
            f.setFilterString(toJson(form.criteria()));
        }

        return toView(f);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        User me = current.get();
        SavedFilter f = filters.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Filter not found"));

        if (!f.getUser().getId().equals(me.getId()) && !access.isAdmin(me)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the author or an administrator can delete this filter");
        }

        filters.delete(f);
    }

    private SavedFilterView toView(SavedFilter f) {
        FilterCriteria crit = toCriteria(f.getFilterString());
        Long pid = f.getProject() != null ? f.getProject().getId() : null;
        String pname = f.getProject() != null ? f.getProject().getName() : null;
        return new SavedFilterView(f.getId(), f.getName(), pid, pname, Mapper.ref(f.getUser()), f.isPublic(), crit);
    }

    private String toJson(FilterCriteria c) {
        if (c == null) return "{}";
        try {
            return mapper.writeValueAsString(c);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private FilterCriteria toCriteria(String json) {
        if (json == null || json.isBlank()) return new FilterCriteria(null, null, null, null, null, null, null, null, true, null, null, null);
        try {
            return mapper.readValue(json, FilterCriteria.class);
        } catch (Exception e) {
            return new FilterCriteria(null, null, null, null, null, null, null, null, true, null, null, null);
        }
    }
}
