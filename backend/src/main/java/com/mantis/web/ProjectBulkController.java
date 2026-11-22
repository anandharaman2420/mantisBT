package com.mantis.web;

import com.mantis.dto.Dtos.*;
import com.mantis.security.CurrentUser;
import com.mantis.service.ProjectBulkService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** Bulk project setup - see ProjectBulkService. All endpoints need the manager role in the project. */
@RestController
@RequestMapping("/api/projects/{pid}")
public class ProjectBulkController {
    private final ProjectBulkService service;
    private final CurrentUser current;

    public ProjectBulkController(ProjectBulkService service, CurrentUser current) {
        this.service = service; this.current = current;
    }

    @PostMapping("/categories/bulk")
    public ImportResult categories(@PathVariable Long pid, @Valid @RequestBody BulkCategoryRequest r) {
        return service.categories(current.get(), pid, r.rows());
    }

    @PostMapping("/versions/bulk")
    public ImportResult versions(@PathVariable Long pid, @Valid @RequestBody BulkVersionRequest r) {
        return service.versions(current.get(), pid, r.rows());
    }

    @PostMapping("/members/bulk")
    public ImportResult members(@PathVariable Long pid, @Valid @RequestBody BulkMemberRequest r) {
        return service.members(current.get(), pid, r);
    }

    @PostMapping("/members/remove-bulk")
    public ImportResult removeMembers(@PathVariable Long pid, @Valid @RequestBody RemoveMembersRequest r) {
        return service.removeMembers(current.get(), pid, r.userIds());
    }

    @PostMapping("/copy-from")
    public CopyResult copyFrom(@PathVariable Long pid, @Valid @RequestBody CopyFromRequest r) {
        return service.copyFrom(current.get(), pid, r);
    }
}
