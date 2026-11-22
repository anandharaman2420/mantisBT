package com.mantis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mantis.domain.*;
import com.mantis.dto.Dtos.*;
import com.mantis.repo.CategoryRepository;
import com.mantis.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Port of Mantis bug_actiongroup_page / bug_actiongroup.php: one action applied to many issues.
 * Every issue runs in its OWN transaction and goes through the normal single-issue service methods, so
 * all permission checks, workflow rules, history entries and e-mails are exactly the same as for a single edit.
 * An issue that fails (no access, read-only ...) is reported, the others are still processed.
 */
@Service
public class BulkService {
    private static final Logger log = LoggerFactory.getLogger(BulkService.class);
    private static final Set<String> ACTIONS = Set.of("assign", "status", "priority", "severity", "view_state", "category",
            "fixed_in_version", "target_version", "sticky", "add_note", "add_tags", "remove_tags", "delete", "move", "copy");

    private final IssueService issues;
    private final IssueExtrasService extras;
    private final UserRepository users;
    private final CategoryRepository categories;
    private final ObjectMapper mapper;
    private final PlatformTransactionManager txManager;

    public BulkService(IssueService issues, IssueExtrasService extras, UserRepository users,
                       CategoryRepository categories, ObjectMapper mapper, PlatformTransactionManager txManager) {
        this.issues = issues; this.extras = extras; this.users = users; this.categories = categories;
        this.mapper = mapper; this.txManager = txManager;
    }

    private static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }

    /** NOT transactional itself - see class comment. */
    public BulkResult run(String username, BulkRequest r) {
        if (r.action() == null) throw bad("Missing parameter: action");
        String action = r.action().trim().toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) throw bad("Unknown action: " + r.action());
        validateParams(action, r);

        TransactionTemplate tx = new TransactionTemplate(txManager);
        List<BulkFailure> failures = new ArrayList<>();
        int ok = 0;
        for (Long id : new LinkedHashSet<>(r.ids())) {
            try {
                tx.executeWithoutResult(st -> {
                    User me = users.findByUsername(username)
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user"));
                    apply(me, id, action, r);
                });
                ok++;
            } catch (ResponseStatusException e) {
                failures.add(new BulkFailure(id, e.getReason() == null ? "Failed" : e.getReason()));
            } catch (RuntimeException e) {
                log.warn("Bulk action {} failed for issue {}", action, id, e);
                failures.add(new BulkFailure(id, "Unexpected error"));
            }
        }
        return new BulkResult(ok, failures);
    }

    private void validateParams(String action, BulkRequest r) {
        switch (action) {
            case "status", "priority", "severity", "view_state", "sticky" -> need(r.intValue() != null, "value");
            case "category", "move", "copy" -> need(r.longValue() != null, "value");
            case "fixed_in_version", "target_version" -> need(r.text() != null, "text");
            case "add_note", "add_tags", "remove_tags" -> need(r.text() != null && !r.text().isBlank(), "text");
            default -> { }           // assign (no value = unassign), delete
        }
    }

    private static void need(boolean cond, String what) { if (!cond) throw bad("Missing parameter: " + what); }

    private void apply(User me, Long id, String action, BulkRequest r) {
        switch (action) {
            case "assign" -> {
                if (r.longValue() == null) issues.update(me, id, form(Map.of("clearHandler", true)));      // unassign
                else issues.changeStatus(me, id, new StatusChange(null, null, r.longValue(), null, null));
            }
            case "status" -> issues.changeStatus(me, id, new StatusChange(r.intValue(), r.resolution(), null, null,
                    r.text() == null || r.text().isBlank() ? null : r.text()));
            case "priority" -> issues.update(me, id, form(Map.of("priority", r.intValue())));
            case "severity" -> issues.update(me, id, form(Map.of("severity", r.intValue())));
            case "view_state" -> issues.update(me, id, form(Map.of("viewState", r.intValue())));
            case "sticky" -> issues.update(me, id, form(Map.of("sticky", r.intValue() == 1)));
            case "fixed_in_version" -> issues.update(me, id, form(Map.of("fixedInVersion", r.text())));
            case "target_version" -> issues.update(me, id, form(Map.of("targetVersion", r.text())));
            case "category" -> {
                // the chosen category belongs to one project; other projects get the category with the same name
                Issue i = issues.getVisible(me, id);
                Category chosen = categories.findById(r.longValue()).orElseThrow(() -> bad("Category not found"));
                Category target = issues.categoryUsable(i.getProject(), chosen) ? chosen
                        : Optional.ofNullable(issues.categoryByName(i.getProject(), chosen.getName()))
                            .orElseThrow(() -> bad("Category \"" + chosen.getName() + "\" does not exist in project " + i.getProject().getName()));
                issues.update(me, id, form(Map.of("categoryId", target.getId())));
            }
            case "add_tags", "remove_tags" -> {
                Issue i = issues.getVisible(me, id);
                Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                i.getTags().forEach(t -> names.add(t.getName()));
                Set<String> change = Arrays.stream(r.text().split(",")).map(String::trim).filter(s -> !s.isEmpty())
                        .collect(Collectors.toCollection(() -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)));
                if (action.equals("add_tags")) names.addAll(change); else names.removeAll(change);
                issues.update(me, id, form(Map.of("tags", new ArrayList<>(names))));
            }
            case "add_note" -> extras.addNote(me, id, new NoteForm(r.text(), r.intValue(), null, null));
            case "delete" -> issues.delete(me, id);
            case "move" -> issues.move(me, id, r.longValue());
            case "copy" -> issues.copy(me, id, new CopyForm(r.longValue(), false, false, true));
            default -> throw bad("Unknown action");
        }
    }

    /** builds an IssueForm from a few named values (the form has ~27 components, so we let Jackson fill the rest with null) */
    private IssueForm form(Map<String, Object> values) { return mapper.convertValue(values, IssueForm.class); }
}
