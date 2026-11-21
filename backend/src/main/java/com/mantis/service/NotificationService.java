package com.mantis.service;

import com.mantis.domain.*;
import com.mantis.repo.MonitorRepository;
import com.mantis.repo.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;

/**
 * Port of Mantis email_api.php (simplified).
 * Recipients: reporter, handler and everyone monitoring the issue - except the person who did the change,
 * users who switched notifications off, and users who may not see the issue (private issue / private note).
 * Mails are built inside the transaction (lazy data) but only sent after it committed.
 */
@Service
public class NotificationService {
    private final MonitorRepository monitors;
    private final UserRepository users;
    private final AccessService access;
    private final MailDispatcher dispatcher;
    private final boolean enabled;
    private final String baseUrl;

    public NotificationService(MonitorRepository monitors, UserRepository users, AccessService access,
                               MailDispatcher dispatcher, @Value("${app.mail.enabled}") boolean enabled,
                               @Value("${app.base-url}") String baseUrl) {
        this.monitors = monitors; this.users = users; this.access = access; this.dispatcher = dispatcher;
        this.enabled = enabled; this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public void issueCreated(User actor, Issue i) {
        send(actor, i, "A NEW ISSUE has been created by " + actor.getUsername() + ".", List.of(), null, false);
    }

    /** @param changes (field, old, new) triples collected by HistoryService, @param note optional note text */
    public void issueUpdated(User actor, Issue i, List<String[]> changes, String note) {
        if (changes.isEmpty() && (note == null || note.isBlank())) return;
        List<String> lines = new ArrayList<>();
        for (String[] c : changes) lines.add(describe(c));
        String headline = i.getStatus() >= Lookups.RESOLVED
                ? "The issue is now " + Lookups.label(Lookups.STATUS, i.getStatus()).toUpperCase() + " (" + actor.getUsername() + ")."
                : "The issue has been UPDATED by " + actor.getUsername() + ".";
        send(actor, i, headline, lines, note == null || note.isBlank() ? null : actor.getUsername() + " wrote:\n" + note, false);
    }

    public void noteAdded(User actor, Issue i, Note n) {
        send(actor, i, "A NOTE has been added to this issue by " + actor.getUsername() + ".", List.of(),
                n.getText(), n.getViewState() == Lookups.VS_PRIVATE);
    }

    // ------------------------------------------------------------------
    private void send(User actor, Issue i, String headline, List<String> changeLines, String noteText, boolean privateNote) {
        if (!enabled) return;
        Map<Long, User> candidates = new LinkedHashMap<>();
        candidates.put(i.getReporter().getId(), i.getReporter());
        if (i.getHandler() != null) candidates.put(i.getHandler().getId(), i.getHandler());
        for (IssueMonitor m : monitors.findByIssueId(i.getId())) candidates.put(m.getUser().getId(), m.getUser());

        String subject = "[" + i.getProject().getName() + " " + String.format("%07d", i.getId()) + "]: " + i.getSummary();
        String body = body(i, headline, changeLines, noteText);
        List<String> to = new ArrayList<>();
        for (User u : candidates.values()) {
            if (u.getId().equals(actor.getId()) || !u.isEnabled() || !u.isEmailEnabled()) continue;
            if (u.getEmail() == null || u.getEmail().isBlank()) continue;
            if (!access.canSee(u, i)) continue;
            if (privateNote && access.levelIn(u, i.getProject()) < Lookups.DEVELOPER) continue;
            to.add(u.getEmail());
        }
        if (to.isEmpty()) return;
        afterCommit(() -> to.forEach(addr -> dispatcher.send(addr, subject, body)));
    }

    private String body(Issue i, String headline, List<String> changeLines, String noteText) {
        StringBuilder b = new StringBuilder();
        b.append(headline).append("\n\n");
        b.append("Issue:     ").append(baseUrl).append("/issues/").append(i.getId()).append('\n');
        b.append("Project:   ").append(i.getProject().getName()).append('\n');
        if (i.getCategory() != null) b.append("Category:  ").append(i.getCategory().getName()).append('\n');
        b.append("Summary:   ").append(i.getSummary()).append('\n');
        b.append("Reporter:  ").append(i.getReporter().getUsername()).append('\n');
        b.append("Assigned:  ").append(i.getHandler() == null ? "-" : i.getHandler().getUsername()).append('\n');
        b.append("Status:    ").append(Lookups.label(Lookups.STATUS, i.getStatus())).append('\n');
        b.append("Priority:  ").append(Lookups.label(Lookups.PRIORITY, i.getPriority()))
                .append("   Severity: ").append(Lookups.label(Lookups.SEVERITY, i.getSeverity())).append('\n');
        if (!changeLines.isEmpty()) {
            b.append("\nChanges:\n");
            changeLines.forEach(l -> b.append(" - ").append(l).append('\n'));
        }
        if (noteText != null && !noteText.isBlank()) b.append("\n").append(noteText).append('\n');
        b.append("\n-- \nYou receive this because you reported, handle or monitor this issue.\n")
                .append("You can switch notifications off under \"My Account\".\n");
        return b.toString();
    }

    private String describe(String[] c) {
        String f = c[0], o = c[1], n = c[2];
        if (o == null && n == null) return f.replace('_', ' ') + " updated";
        return f.replace('_', ' ') + ": " + show(f, o) + " -> " + show(f, n);
    }

    private String show(String field, String v) {
        if (v == null || v.isBlank()) return "(none)";
        try {
            switch (field) {
                case "status": return Lookups.label(Lookups.STATUS, Integer.parseInt(v));
                case "resolution": return Lookups.label(Lookups.RESOLUTION, Integer.parseInt(v));
                case "priority": return Lookups.label(Lookups.PRIORITY, Integer.parseInt(v));
                case "severity": return Lookups.label(Lookups.SEVERITY, Integer.parseInt(v));
                case "reproducibility": return Lookups.label(Lookups.REPRODUCIBILITY, Integer.parseInt(v));
                case "view_state": return Lookups.label(Lookups.VIEW_STATE, Integer.parseInt(v));
                case "handler_id": return users.findById(Long.parseLong(v)).map(User::getUsername).orElse("#" + v);
                default: return v;
            }
        } catch (NumberFormatException e) { return v; }
    }

    private void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { r.run(); }
            });
        } else {
            r.run();
        }
    }
}
