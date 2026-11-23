package com.mantis.api.rest;

import com.mantis.api.rest.RestDtos.*;
import com.mantis.domain.*;
import com.mantis.dto.Dtos.*;

import java.util.List;
import java.util.Map;

/** Converts between the application DTOs / entities and the Mantis-compatible REST DTOs. */
public final class RestMapper {
    private RestMapper() {}

    // ------------------------------------------------------------------ primitives

    public static EnumRefDto enumRef(Map<Integer, String> values, int value) {
        String label = Lookups.label(values, value);
        return new EnumRefDto(value, label, label);
    }

    public static UserDto user(User u) {
        return u == null ? null : new UserDto(u.getId(), u.getUsername(), u.getRealname(), u.getEmail(), u.isEnabled());
    }

    public static UserDto user(UserRef r) {
        return r == null ? null : new UserDto(r.id(), r.username(), r.realname(), null, null);
    }

    public static RefDto ref(Project p) {
        return p == null ? null : new RefDto(p.getId(), p.getName());
    }

    public static RefDto ref(Category c) {
        return c == null ? null : new RefDto(c.getId(), c.getName());
    }

    public static TagDto tag(Tag t) {
        return new TagDto(t.getId(), t.getName(), t.getDescription());
    }

    // ------------------------------------------------------------------ entities / views

    public static ProjectDto project(Project p) {
        return new ProjectDto(p.getId(), p.getName(), p.getDescription(),
                enumRef(Lookups.PROJECT_STATUS, p.getStatus()),
                enumRef(Lookups.VIEW_STATE, p.getViewState()), p.isEnabled());
    }

    public static CategoryDto category(Category c) {
        return new CategoryDto(c.getId(), c.getName(), ref(c.getProject()));
    }

    public static VersionDto version(ProjectVersion v) {
        return new VersionDto(v.getId(), v.getVersion(), v.getDescription(), v.isReleased(), v.isObsolete(),
                ref(v.getProject()));
    }

    public static NoteDto note(NoteView n) {
        return new NoteDto(n.id(), n.text(), user(n.reporter()),
                enumRef(Lookups.VIEW_STATE, n.viewState()),
                enumRef(Lookups.NOTE_TYPE, n.noteType()),
                n.dateSubmitted() == null ? null : n.dateSubmitted().toString(),
                n.lastModified() == null ? null : n.lastModified().toString());
    }

    public static FileDto file(AttachmentView a) {
        return new FileDto(a.id(), a.filename(), a.filesize(), a.fileType(), null,
                a.dateAdded() == null ? null : a.dateAdded().toString());
    }

    public static RelationshipDto relationship(RelationshipView r) {
        return new RelationshipDto(r.id(), enumRef(Lookups.RELATIONSHIP, r.type()),
                new IssueRefDto(r.otherIssueId(), r.otherSummary()));
    }

    public static IssueDto issue(IssueView v, List<NoteDto> notes, Map<String, TagDto> tagIndex) {
        List<TagDto> tags = v.tags() == null ? List.of() : v.tags().stream()
                .map(name -> tagIndex.getOrDefault(name.toLowerCase(), new TagDto(null, name, null)))
                .toList();
        List<CustomFieldDto> fields = v.customFields() == null ? List.of() : v.customFields().stream()
                .map(f -> new CustomFieldDto(f.fieldId(), f.name(), f.value()))
                .toList();
        return new IssueDto(v.id(),
                new RefDto(v.projectId(), v.projectName()),
                v.categoryId() == null ? null : new RefDto(v.categoryId(), v.category()),
                user(v.reporter()), user(v.handler()),
                v.summary(), v.description(), v.stepsToReproduce(), v.additionalInformation(),
                enumRef(Lookups.STATUS, v.status()), enumRef(Lookups.RESOLUTION, v.resolution()),
                enumRef(Lookups.PRIORITY, v.priority()), enumRef(Lookups.SEVERITY, v.severity()),
                enumRef(Lookups.REPRODUCIBILITY, v.reproducibility()),
                enumRef(Lookups.PROJECTION, v.projection()), enumRef(Lookups.ETA, v.eta()),
                enumRef(Lookups.VIEW_STATE, v.viewState()),
                v.os(), v.osBuild(), v.platform(), v.version(), v.fixedInVersion(), v.targetVersion(), v.build(),
                v.sticky(),
                v.dateSubmitted() == null ? null : v.dateSubmitted().toString(),
                v.lastUpdated() == null ? null : v.lastUpdated().toString(),
                notes == null ? List.of() : notes, tags, fields);
    }

    // ------------------------------------------------------------------ inputs -> internal forms

    /** resolves an enum reference by id, or by (case-insensitive) name. */
    public static Integer enumId(RefDto ref, Map<Integer, String> values) {
        if (ref == null) return null;
        if (ref.id() != null) return ref.id().intValue();
        if (ref.name() != null) {
            String wanted = ref.name().trim();
            for (Map.Entry<Integer, String> e : values.entrySet())
                if (e.getValue().equalsIgnoreCase(wanted)) return e.getKey();
        }
        return null;
    }

    private static Long id(RefDto ref) {
        return ref == null ? null : (ref.id() == null ? null : ref.id().longValue());
    }

    public static IssueForm toForm(IssueInputDto in) {
        return new IssueForm(
                id(in.project()), id(in.category()),
                in.summary(), in.description(), in.stepsToReproduce(), in.additionalInformation(),
                enumId(in.priority(), Lookups.PRIORITY),
                enumId(in.severity(), Lookups.SEVERITY),
                enumId(in.reproducibility(), Lookups.REPRODUCIBILITY),
                enumId(in.status(), Lookups.STATUS),
                enumId(in.resolution(), Lookups.RESOLUTION),
                enumId(in.projection(), Lookups.PROJECTION),
                enumId(in.eta(), Lookups.ETA),
                id(in.handler()), null, null,
                in.os(), in.osBuild(), in.platform(), in.version(), in.fixedInVersion(), in.targetVersion(),
                in.build(),
                enumId(in.viewState(), Lookups.VIEW_STATE),
                in.sticky(), in.tags(), null);
    }

    public static NoteForm toNoteForm(NoteInputDto in) {
        return new NoteForm(in.text(), enumId(in.viewState(), Lookups.VIEW_STATE),
                enumId(in.noteType(), Lookups.NOTE_TYPE), null);
    }

    /** form that only replaces the issue's tags (used to attach/detach a single tag). */
    public static IssueForm tagsForm(List<String> names) {
        return new IssueForm(null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, names, null);
    }
}
