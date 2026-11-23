package com.mantis.web;

import com.mantis.domain.*;
import com.mantis.dto.Dtos.*;
import com.mantis.repo.NewsRepository;
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

/** news_api / news_*.php  (project == null means site-wide news) */
@RestController
@RequestMapping("/api/news")
@Transactional
public class NewsController {
    private final NewsRepository news;
    private final CurrentUser current;
    private final AccessService access;

    public NewsController(NewsRepository news, CurrentUser current, AccessService access) {
        this.news = news; this.current = current; this.access = access;
    }

    @GetMapping
    public List<NewsView> list(@RequestParam(required = false) Long projectId) {
        User me = current.get();
        Map<Long, Integer> levels = access.levels(me);
        return news.findAllByOrderByDatePostedDesc().stream()
                .filter(n -> projectId == null || n.getProject() == null || n.getProject().getId().equals(projectId))
                .filter(n -> n.getProject() == null || levels.containsKey(n.getProject().getId()))
                .filter(n -> n.getViewState() == Lookups.VS_PUBLIC
                        || access.isAdmin(me)
                        || (n.getProject() != null && levels.getOrDefault(n.getProject().getId(), 0) >= Lookups.DEVELOPER))
                .limit(50).map(Mapper::news).toList();
    }

    @PostMapping
    public NewsView create(@Valid @RequestBody NewsForm f) {
        User me = current.get();
        News n = new News();
        if (f.projectId() == null) {
            access.requireAdmin(me);
        } else {
            Project p = access.projectOrThrow(f.projectId());
            access.require(me, p, Lookups.MANAGER);
            n.setProject(p);
        }
        n.setPoster(me);
        n.setHeadline(f.headline().trim());
        n.setBody(f.body());
        if (f.viewState() != null && Lookups.VIEW_STATE.containsKey(f.viewState())) n.setViewState(f.viewState());
        if (f.announcement() != null) n.setAnnouncement(f.announcement());
        return Mapper.news(news.save(n));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        User me = current.get();
        News n = news.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "News not found"));
        if (n.getProject() == null) access.requireAdmin(me); else access.require(me, n.getProject(), Lookups.MANAGER);
        news.delete(n);
    }
}
