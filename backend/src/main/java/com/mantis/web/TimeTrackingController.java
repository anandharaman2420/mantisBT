package com.mantis.web;

import com.mantis.domain.Lookups;
import com.mantis.domain.Note;
import com.mantis.domain.User;
import com.mantis.dto.Dtos.*;
import com.mantis.repo.NoteRepository;
import com.mantis.security.CurrentUser;
import com.mantis.service.AccessService;
import com.mantis.service.Mapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/** billing_page.php / time tracking report: only for projects where the user is a manager (time_tracking_reporting_threshold) */
@RestController
@RequestMapping("/api/time-tracking")
@Transactional(readOnly = true)
public class TimeTrackingController {
    private final NoteRepository notes;
    private final CurrentUser current;
    private final AccessService access;
    private final boolean enabled;

    public TimeTrackingController(NoteRepository notes, CurrentUser current, AccessService access,
                                  @Value("${app.time-tracking.enabled}") boolean enabled) {
        this.notes = notes; this.current = current; this.access = access; this.enabled = enabled;
    }

    @GetMapping
    public TimeReport report(@RequestParam(required = false) Long projectId,
                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Time tracking is disabled");
        if (to.isBefore(from)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'to' must not be before 'from'");
        if (from.plusDays(366).isBefore(to)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Range is limited to one year");
        User me = current.get();
        Set<Long> managed = access.levels(me).entrySet().stream().filter(e -> e.getValue() >= Lookups.MANAGER)
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        if (projectId != null) {
            if (!managed.contains(projectId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
            managed = Set.of(projectId);
        }
        if (managed.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");

        List<TimeEntry> entries = new ArrayList<>();
        Map<Long, Long> perUser = new LinkedHashMap<>();
        Map<Long, UserRef> userRefs = new HashMap<>();
        long total = 0;
        for (Note n : notes.timeEntries(from.atStartOfDay().toInstant(ZoneOffset.UTC),
                to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC), managed)) {
            UserRef u = Mapper.ref(n.getReporter());
            entries.add(new TimeEntry(n.getIssue().getId(), n.getIssue().getSummary(), n.getIssue().getProject().getName(),
                    u, n.getDateSubmitted(), n.getTimeTracking(), n.getText()));
            perUser.merge(u.id(), (long) n.getTimeTracking(), Long::sum);
            userRefs.put(u.id(), u);
            total += n.getTimeTracking();
        }
        List<TimeUserTotal> byUser = perUser.entrySet().stream()
                .map(e -> new TimeUserTotal(userRefs.get(e.getKey()), e.getValue()))
                .sorted((a, b) -> Long.compare(b.minutes(), a.minutes())).toList();
        return new TimeReport(entries, byUser, total);
    }
}
