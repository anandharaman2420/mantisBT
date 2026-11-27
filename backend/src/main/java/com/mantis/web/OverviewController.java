package com.mantis.web;

import com.mantis.dto.Dtos.MyView;
import com.mantis.security.CurrentUser;
import com.mantis.service.MyViewService;
import com.mantis.service.ReleaseNotes;
import com.mantis.service.ReleaseNotesService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** My View, roadmap and changelog pages. The services are transactional and return plain DTOs. */
@RestController
@RequestMapping("/api")
public class OverviewController {
    private final MyViewService myView;
    private final ReleaseNotesService releaseNotes;
    private final CurrentUser current;

    public OverviewController(MyViewService myView, ReleaseNotesService releaseNotes, CurrentUser current) {
        this.myView = myView; this.releaseNotes = releaseNotes; this.current = current;
    }

    @GetMapping("/my-view")
    public MyView myView(@RequestParam(required = false) Long projectId) { return myView.view(current.get(), projectId); }

    @GetMapping("/roadmap")
    public ReleaseNotes.Result roadmap(@RequestParam(required = false) Long projectId) {
        return releaseNotes.roadmap(current.get(), projectId);
    }

    @GetMapping("/changelog")
    public ReleaseNotes.Result changelog(@RequestParam(required = false) Long projectId) {
        return releaseNotes.changelog(current.get(), projectId);
    }
}
