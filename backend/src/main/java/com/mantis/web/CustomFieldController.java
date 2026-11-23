package com.mantis.web;

import com.mantis.domain.*;
import com.mantis.dto.Dtos.*;
import com.mantis.repo.*;
import com.mantis.security.CurrentUser;
import com.mantis.service.AccessService;
import com.mantis.service.CustomFieldService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** manage_custom_field_*.php (administrator) and manage_proj_edit_page custom field linking (project manager) */
@RestController
@RequestMapping("/api")
@Transactional
public class CustomFieldController {
    private final CustomFieldRepository fields;
    private final CustomFieldProjectRepository links;
    private final CustomFieldValueRepository values;
    private final CurrentUser current;
    private final AccessService access;
    private final CustomFieldService service;

    public CustomFieldController(CustomFieldRepository fields, CustomFieldProjectRepository links,
                                 CustomFieldValueRepository values, CurrentUser current, AccessService access,
                                 CustomFieldService service) {
        this.fields = fields; this.links = links; this.values = values; this.current = current; this.access = access; this.service = service;
    }

    private ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }

    // ---------------------------------------------------------------- definitions (administrator + developer read-only)
    @GetMapping("/custom-fields")
    public List<CustomFieldView> list() {
        User me = current.get();
        if (me.getAccessLevel() < Lookups.DEVELOPER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        Map<Long, List<Long>> fieldToProjects = links.findAll().stream()
                .collect(Collectors.groupingBy(l -> l.getField().getId(),
                        Collectors.mapping(l -> l.getProject().getId(), Collectors.toList())));
        return fields.findAllByOrderByName().stream()
                .map(f -> CustomFieldService.view(f, fieldToProjects.getOrDefault(f.getId(), List.of())))
                .toList();
    }

    @PostMapping("/custom-fields")
    public CustomFieldView create(@Valid @RequestBody CustomFieldForm f) {
        access.requireAdmin(current.get());
        if (fields.existsByNameIgnoreCase(f.name().trim())) throw new ResponseStatusException(HttpStatus.CONFLICT, "A custom field with this name already exists");
        CustomField c = new CustomField();
        apply(c, f);
        CustomField saved = fields.save(c);
        if (f.projectIds() != null && !f.projectIds().isEmpty()) {
            syncProjectLinks(saved, f.projectIds());
        }
        List<Long> pids = links.findByFieldId(saved.getId()).stream().map(l -> l.getProject().getId()).toList();
        return CustomFieldService.view(saved, pids);
    }

    @PutMapping("/custom-fields/{id}")
    public CustomFieldView update(@PathVariable Long id, @Valid @RequestBody CustomFieldForm f) {
        access.requireAdmin(current.get());
        CustomField c = fields.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Custom field not found"));
        if (!c.getName().equalsIgnoreCase(f.name().trim()) && fields.existsByNameIgnoreCase(f.name().trim()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A custom field with this name already exists");
        apply(c, f);
        if (f.projectIds() != null) {
            syncProjectLinks(c, f.projectIds());
        }
        List<Long> pids = links.findByFieldId(c.getId()).stream().map(l -> l.getProject().getId()).toList();
        return CustomFieldService.view(c, pids);
    }

    private void syncProjectLinks(CustomField field, List<Long> targetProjectIds) {
        List<CustomFieldProject> existing = links.findByFieldId(field.getId());
        Set<Long> targetSet = new HashSet<>(targetProjectIds);
        for (CustomFieldProject l : existing) {
            if (!targetSet.contains(l.getProject().getId())) {
                links.delete(l);
                values.deleteByFieldAndProject(field.getId(), l.getProject().getId());
            }
        }
        Set<Long> existingProjectIds = existing.stream().map(l -> l.getProject().getId()).collect(Collectors.toSet());
        for (Long pid : targetProjectIds) {
            if (!existingProjectIds.contains(pid)) {
                Project p = access.projectOrThrow(pid);
                CustomFieldProject n = new CustomFieldProject();
                n.setField(field);
                n.setProject(p);
                n.setSequence((int) links.findByProjectIdOrderBySequenceAscIdAsc(pid).size() + 1);
                links.save(n);
            }
        }
    }

    @DeleteMapping("/custom-fields/{id}")
    public void delete(@PathVariable Long id) {
        access.requireAdmin(current.get());
        CustomField c = fields.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Custom field not found"));
        values.deleteByFieldId(id);
        links.deleteByFieldId(id);
        fields.delete(c);
    }

    private void apply(CustomField c, CustomFieldForm f) {
        c.setName(f.name().trim());
        if (f.type() != null) {
            if (f.type() < 0 || f.type() > 10) throw bad("Invalid field type");
            c.setType(f.type());
        }
        if (f.possibleValues() != null) c.setPossibleValues(f.possibleValues());
        if (f.defaultValue() != null) c.setDefaultValue(f.defaultValue());
        if (f.validRegexp() != null) {
            try { java.util.regex.Pattern.compile(f.validRegexp()); } catch (Exception e) { throw bad("Invalid regular expression"); }
            c.setValidRegexp(f.validRegexp());
        }
        if (f.accessLevelR() != null) c.setAccessLevelR(f.accessLevelR());
        if (f.accessLevelRw() != null) c.setAccessLevelRw(f.accessLevelRw());
        if (f.lengthMin() != null) c.setLengthMin(f.lengthMin());
        if (f.lengthMax() != null) c.setLengthMax(f.lengthMax());
        if (f.requireReport() != null) c.setRequireReport(f.requireReport());
        if (f.requireUpdate() != null) c.setRequireUpdate(f.requireUpdate());
        if (f.displayReport() != null) c.setDisplayReport(f.displayReport());
        if (f.displayUpdate() != null) c.setDisplayUpdate(f.displayUpdate());
        if (f.displayResolved() != null) c.setDisplayResolved(f.displayResolved());
        if (f.displayClosed() != null) c.setDisplayClosed(f.displayClosed());
        if (f.filterBy() != null) c.setFilterBy(f.filterBy());
        boolean choice = Set.of(CustomField.ENUM, CustomField.LIST, CustomField.RADIO, CustomField.CHECKBOX, CustomField.MULTILIST).contains(c.getType());
        if (choice && CustomFieldService.options(c).isEmpty()) throw bad("Possible values are required for this field type (separate with |)");
        if (c.getAccessLevelRw() < c.getAccessLevelR()) throw bad("Write access level cannot be lower than read access level");
    }

    // ---------------------------------------------------------------- project <-> field links (project manager)
    @GetMapping("/projects/{pid}/custom-field-links")
    public List<CustomFieldView> linked(@PathVariable Long pid) {
        Project p = access.projectOrThrow(pid);
        access.require(current.get(), p, Lookups.MANAGER);
        return links.findByProjectIdOrderBySequenceAscIdAsc(pid).stream().map(l -> CustomFieldService.view(l.getField())).toList();
    }

    @GetMapping("/projects/{pid}/custom-field-links/available")
    public List<CustomFieldView> available(@PathVariable Long pid) {
        Project p = access.projectOrThrow(pid);
        access.require(current.get(), p, Lookups.MANAGER);
        Set<Long> used = links.findByProjectIdOrderBySequenceAscIdAsc(pid).stream().map(l -> l.getField().getId()).collect(Collectors.toSet());
        return fields.findAllByOrderByName().stream().filter(f -> !used.contains(f.getId())).map(CustomFieldService::view).toList();
    }

    @PutMapping("/projects/{pid}/custom-field-links")
    public void link(@PathVariable Long pid, @Valid @RequestBody FieldLinkForm f) {
        Project p = access.projectOrThrow(pid);
        access.require(current.get(), p, Lookups.MANAGER);
        CustomField field = fields.findById(f.fieldId()).orElseThrow(() -> bad("Custom field not found"));
        CustomFieldProject l = links.findByFieldIdAndProjectId(field.getId(), pid).orElseGet(() -> {
            CustomFieldProject n = new CustomFieldProject(); n.setField(field); n.setProject(p); return n;
        });
        if (f.sequence() != null) l.setSequence(f.sequence());
        else if (l.getId() == null) l.setSequence((int) links.findByProjectIdOrderBySequenceAscIdAsc(pid).size() + 1);
        links.save(l);
    }

    @DeleteMapping("/projects/{pid}/custom-field-links/{fieldId}")
    public void unlink(@PathVariable Long pid, @PathVariable Long fieldId) {
        Project p = access.projectOrThrow(pid);
        access.require(current.get(), p, Lookups.MANAGER);
        links.findByFieldIdAndProjectId(fieldId, pid).ifPresent(links::delete);
        values.deleteByFieldAndProject(fieldId, pid);      // like Mantis: values of this project's issues are removed
    }

    // ---------------------------------------------------------------- fields for the "report issue" form
    @GetMapping("/projects/{pid}/custom-fields")
    public List<IssueFieldValue> forReport(@PathVariable Long pid) {
        Project p = access.projectOrThrow(pid);
        int lvl = access.require(current.get(), p, Lookups.REPORTER);
        return service.forReport(p, lvl);
    }
}
