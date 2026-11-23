package com.mantis.api.rest;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * Request / response shapes of the Mantis-compatible REST API ({@code /api/rest}).
 * Field names are serialized in snake_case to match the MantisBT REST conventions
 * (e.g. {@code real_name}, {@code view_state}, {@code date_submitted}).
 */
public final class RestDtos {
    private RestDtos() {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RefDto(Long id, String name) {}

    /** Mantis enum value: {@code {"id":10,"name":"new","label":"new"}}. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EnumRefDto(Integer id, String name, String label) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UserDto(Long id, String name, String realName, String email, Boolean enabled) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ProjectDto(Long id, String name, String description,
                             EnumRefDto status, EnumRefDto viewState, Boolean enabled) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CategoryDto(Long id, String name, RefDto project) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record VersionDto(Long id, String name, String description, Boolean released,
                             Boolean obsolete, RefDto project) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TagDto(Long id, String name, String description) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record NoteDto(Long id, String text, UserDto reporter, EnumRefDto viewState, EnumRefDto noteType,
                          String createdAt, String updatedAt) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FileDto(Long id, String filename, Long size, String contentType, UserDto user, String createdAt) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record IssueRefDto(Long id, String summary) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RelationshipDto(Long id, EnumRefDto type, IssueRefDto issue) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CustomFieldDto(Long id, String name, String value) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record IssueDto(Long id, RefDto project, RefDto category, UserDto reporter, UserDto handler,
                           String summary, String description, String stepsToReproduce, String additionalInformation,
                           EnumRefDto status, EnumRefDto resolution, EnumRefDto priority, EnumRefDto severity,
                           EnumRefDto reproducibility, EnumRefDto projection, EnumRefDto eta, EnumRefDto viewState,
                           String os, String osBuild, String platform, String version, String fixedInVersion,
                           String targetVersion, String build, Boolean sticky, String createdAt, String updatedAt,
                           List<NoteDto> notes, List<TagDto> tags, List<CustomFieldDto> customFields) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record IssueListDto(List<IssueDto> issues, Long totalCount, Integer page, Integer pageSize) {}

    // ------------------------------------------------------------------ inputs

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record IssueInputDto(RefDto project, RefDto category, RefDto handler, RefDto priority, RefDto severity,
                                RefDto status, RefDto resolution, RefDto reproducibility, RefDto projection,
                                RefDto eta, String summary, String description, String stepsToReproduce,
                                String additionalInformation, String os, String osBuild, String platform,
                                String version, String fixedInVersion, String targetVersion, String build,
                                RefDto viewState, Boolean sticky, List<String> tags) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record NoteInputDto(String text, RefDto viewState, RefDto noteType) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TagInputDto(RefDto tag, String name) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RelationshipInputDto(RefDto type, RefDto issue) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MonitorInputDto(RefDto user) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ErrorDto(Integer code, String message) {}
}
