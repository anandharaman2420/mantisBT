package com.mantis.web;

import com.mantis.security.CurrentUser;
import com.mantis.service.SummaryReport;
import com.mantis.service.SummaryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reports. (The small dashboard numbers stay at GET /api/summary.) */
@RestController
@RequestMapping("/api/reports")
public class ReportsController {
    private final SummaryService summary;
    private final CurrentUser current;

    public ReportsController(SummaryService summary, CurrentUser current) { this.summary = summary; this.current = current; }

    @GetMapping("/summary")
    public SummaryReport.Report summary(@RequestParam(required = false) Long projectId) {
        return summary.report(current.get(), projectId);
    }
}
