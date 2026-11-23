package com.mantis.web;

import com.mantis.domain.Lookups;
import com.mantis.domain.Tag;
import com.mantis.dto.Dtos.*;
import com.mantis.repo.TagRepository;
import com.mantis.security.CurrentUser;
import com.mantis.service.AccessService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

/** tag_api / manage_tags_page */
@RestController
@RequestMapping("/api/tags")
@Transactional
public class TagController {
    private final TagRepository tags;
    private final CurrentUser current;
    private final AccessService access;

    public TagController(TagRepository tags, CurrentUser current, AccessService access) {
        this.tags = tags; this.current = current; this.access = access;
    }

    @GetMapping
    public List<TagView> list() {
        current.get();
        return tags.findAllByOrderByName().stream().map(t -> new TagView(t.getId(), t.getName(), t.getDescription())).toList();
    }

    @PostMapping
    public TagView create(@Valid @RequestBody TagForm f) {
        if (current.get().getAccessLevel() < Lookups.REPORTER) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        if (tags.findByNameIgnoreCase(f.name().trim()).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Tag already exists");
        Tag t = new Tag();
        t.setName(f.name().trim());
        if (f.description() != null) t.setDescription(f.description());
        t.setCreator(current.get());
        tags.save(t);
        return new TagView(t.getId(), t.getName(), t.getDescription());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        access.requireAdmin(current.get());
        Tag t = tags.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tag not found"));
        tags.delete(t);
        tags.flush();
    }
}
