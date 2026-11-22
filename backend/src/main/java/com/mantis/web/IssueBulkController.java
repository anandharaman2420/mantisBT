package com.mantis.web;

import com.mantis.domain.Issue;
import com.mantis.domain.User;
import com.mantis.dto.Dtos.*;
import com.mantis.security.CurrentUser;
import com.mantis.service.BulkService;
import com.mantis.service.IssueService;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** bug_actiongroup, bug_move, bug_copy. Deliberately NOT class-level @Transactional: bulk runs one transaction per issue. */
@RestController
@RequestMapping("/api")
public class IssueBulkController {
    private final BulkService bulk;
    private final IssueService issues;
    private final CurrentUser current;

    public IssueBulkController(BulkService bulk, IssueService issues, CurrentUser current) {
        this.bulk = bulk; this.issues = issues; this.current = current;
    }

    @PostMapping("/issues/bulk")
    public BulkResult bulk(@Valid @RequestBody BulkRequest r) {
        return bulk.run(current.get().getUsername(), r);
    }

    @PostMapping("/issues/{id}/move")
    @Transactional
    public IssueView move(@PathVariable Long id, @Valid @RequestBody MoveForm f) {
        User me = current.get();
        Issue i = issues.move(me, id, f.projectId());
        return issues.view(me, i);
    }

    @PostMapping("/issues/{id}/copy")
    @Transactional
    public IssueView copy(@PathVariable Long id, @Valid @RequestBody CopyForm f) {
        User me = current.get();
        Issue n = issues.copy(me, id, f);
        return issues.view(me, n);
    }
}
