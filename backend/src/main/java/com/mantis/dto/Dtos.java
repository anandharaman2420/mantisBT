package com.mantis.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

/** All request / response shapes of the REST API. */
public final class Dtos {
    private Dtos() {}

    public record UserRef(Long id, String username, String realname) {}
    public record UserView(Long id, String username, String realname, String email, int accessLevel,
                           boolean enabled, boolean protectedUser, Instant dateCreated, Instant lastVisit,
                           boolean emailEnabled) {}
    public record Preferences(Boolean emailEnabled) {}
    public record UserForm(@NotBlank @Size(max = 191) String username, String realname, @Email String email,
                           String password, Integer accessLevel, Boolean enabled) {}
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record LoginResponse(String token, UserView user) {}
    public record ChangePassword(@NotBlank String currentPassword, @NotBlank @Size(min = 6) String newPassword) {}

    public record ProjectView(Long id, String name, int status, boolean enabled, int viewState,
                              String description, int myAccess) {}
    public record ProjectForm(@NotBlank @Size(max = 128) String name, Integer status, Boolean enabled,
                              Integer viewState, String description) {}
    public record CategoryView(Long id, String name, Long defaultHandlerId, String defaultHandlerName) {}
    public record CategoryForm(@NotBlank @Size(max = 128) String name, Long defaultHandlerId) {}
    public record VersionView(Long id, String version, String description, boolean released, boolean obsolete,
                              Instant dateOrder) {}
    public record VersionForm(@NotBlank @Size(max = 64) String version, String description,
                              Boolean released, Boolean obsolete) {}
    public record MemberView(Long userId, String username, String realname, int accessLevel) {}
    public record MemberForm(@NotNull Long userId, @NotNull Integer accessLevel) {}
    public record TagView(Long id, String name, String description) {}
    public record TagForm(@NotBlank @Size(max = 100) String name, String description) {}

    public record IssueSummary(Long id, Long projectId, String projectName, String summary, int status,
                               int resolution, int priority, int severity, UserRef reporter, UserRef handler,
                               String category, Instant dateSubmitted, Instant lastUpdated, int viewState,
                               boolean sticky) {}

    public record RelationshipView(Long id, int type, String typeLabel, Long otherIssueId, String otherSummary,
                                   int otherStatus) {}

    public record IssueView(Long id, Long projectId, String projectName, Long categoryId, String category,
                            String summary, String description, String stepsToReproduce, String additionalInformation,
                            int status, int resolution, int priority, int severity, int reproducibility,
                            int projection, int eta, UserRef reporter, UserRef handler, Long duplicateId,
                            String os, String osBuild, String platform, String version, String fixedInVersion,
                            String targetVersion, String build, int viewState, boolean sticky,
                            Instant dateSubmitted, Instant lastUpdated, List<String> tags, List<UserRef> monitors,
                            boolean monitoring, boolean canUpdate, boolean canChangeStatus, boolean canDelete,
                            boolean canAddNote, boolean canAttach, List<IssueFieldValue> customFields) {}

    /** Used for create (nulls -> defaults) and update (nulls -> unchanged). */
    public record IssueForm(Long projectId, Long categoryId, @Size(max = 128) String summary, String description,
                            String stepsToReproduce, String additionalInformation, Integer priority,
                            Integer severity, Integer reproducibility, Integer status, Integer resolution,
                            Integer projection, Integer eta, Long handlerId, Boolean clearHandler, Long duplicateId,
                            String os, String osBuild, String platform, String version, String fixedInVersion,
                            String targetVersion, String build, Integer viewState, Boolean sticky,
                            List<String> tags, java.util.Map<Long, String> customFields) {}

    public record StatusChange(@NotNull Integer status, Integer resolution, Long handlerId, Long duplicateId,
                               String note) {}

    public record NoteView(Long id, UserRef reporter, String text, int viewState, int noteType,
                           Instant dateSubmitted, Instant lastModified, boolean canEdit) {}
    public record NoteForm(@NotBlank String text, Integer viewState, Integer noteType) {}

    public record AttachmentView(Long id, String title, String filename, Long filesize, String fileType,
                                 Instant dateAdded, boolean canDelete) {}

    public record HistoryView(Long id, UserRef user, String field, String oldValue, String newValue, int type,
                              Instant date) {}

    public record RelationshipForm(@NotNull Integer type, @NotNull Long otherIssueId) {}
    public record MonitorForm(Long userId) {}

    public record NewsView(Long id, Long projectId, UserRef poster, String headline, String body, int viewState,
                           boolean announcement, Instant datePosted) {}
    public record NewsForm(Long projectId, @NotBlank @Size(max = 64) String headline, @NotBlank String body,
                           Integer viewState, Boolean announcement) {}

    public record CustomFieldView(Long id, String name, int type, String possibleValues, String defaultValue,
                                  String validRegexp, int accessLevelR, int accessLevelRw, int lengthMin, int lengthMax,
                                  boolean requireReport, boolean requireUpdate, boolean displayReport,
                                  boolean displayUpdate, boolean displayResolved, boolean displayClosed, boolean filterBy,
                                  List<Long> projectIds) {}
    public record CustomFieldForm(@NotBlank @Size(max = 64) String name, Integer type, String possibleValues,
                                  String defaultValue, String validRegexp, Integer accessLevelR, Integer accessLevelRw,
                                  Integer lengthMin, Integer lengthMax, Boolean requireReport, Boolean requireUpdate,
                                  Boolean displayReport, Boolean displayUpdate, Boolean displayResolved,
                                  Boolean displayClosed, Boolean filterBy, List<Long> projectIds) {}
    public record FieldLinkForm(@NotNull Long fieldId, Integer sequence) {}
    /** one custom field of one issue (or of the report form): definition + current value */
    public record IssueFieldValue(Long fieldId, String name, int type, String value, List<String> options,
                                  boolean required, boolean canEdit) {}

    public record MoveForm(@NotNull Long projectId) {}
    public record CopyForm(Long projectId, Boolean copyNotes, Boolean copyAttachments, Boolean linkToOriginal) {}
    /** action: assign | status | priority | severity | view_state | category | fixed_in_version | target_version |
     *  sticky | add_note | add_tags | remove_tags | delete | move | copy */
    public record BulkRequest(@NotEmpty @Size(max = 200) List<Long> ids, @NotBlank String action, Integer intValue,
                              Long longValue, String text, Integer resolution) {}
    public record BulkFailure(Long issueId, String message) {}
    public record BulkResult(int succeeded, List<BulkFailure> failures) {}

    public record PageResult<T>(List<T> items, long total, int page, int size) {}
    public record CountEntry(int key, String label, long count) {}
    public record SummaryView(long total, List<CountEntry> byStatus, List<CountEntry> byPriority,
                              List<CountEntry> bySeverity, long unassigned, long open) {}

    public record FilterCriteria(Long projectId, List<Integer> status, Long handlerId, Long reporterId,
                                 Integer priority, Integer severity, Long categoryId, String q,
                                 Boolean hideClosed, String tag, String sortBy, String dir) {}
    public record SavedFilterView(Long id, String name, Long projectId, String projectName,
                                  UserRef user, boolean isPublic, FilterCriteria criteria) {}
    public record SavedFilterForm(@NotBlank @Size(max = 64) String name, Long projectId,
                                  Boolean isPublic, FilterCriteria criteria) {}
}
