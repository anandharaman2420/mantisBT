package com.mantis.service;

import java.time.Instant;
import java.util.*;

/**
 * Roadmap and changelog grouping (port of Mantis roadmap_page.php / changelog_page.php) as plain Java without any
 * framework dependency, so the rules can be unit tested. ReleaseNotesService feeds it with the data the user may see.
 *
 *  Roadmap  : versions that are NOT released and NOT obsolete, with the issues whose TARGET version is that version
 *             (open and already resolved ones: progress = resolved / total). Soonest version first.
 *  Changelog: versions that are not obsolete (released or not), with the RESOLVED / closed issues whose FIXED-IN version
 *             is that version. Newest version first.
 * Versions are per project; names are compared ignoring case and surrounding blanks. Versions without issues are left out.
 */
public final class ReleaseNotes {
    private ReleaseNotes() {}

    public static final int RESOLVED = 80;

    public record VersionInfo(Long projectId, String projectName, Long versionId, String version, String description,
                              Instant dateOrder, boolean released, boolean obsolete) {}

    public record IssueInfo(Long id, Long projectId, String summary, int status, int resolution, int priority,
                            String handler, String category, String targetVersion, String fixedInVersion) {}

    public record Item(Long id, String summary, int status, int resolution, int priority, String handler, String category) {}

    public record Block(Long projectId, String projectName, Long versionId, String version, String description,
                        Instant dateOrder, boolean released, int total, int resolved, List<Item> issues) {}

    /** truncated = more issues exist than were loaded (the lists are then incomplete) */
    public record Result(List<Block> versions, boolean truncated) {}

    static String norm(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }

    public static List<Block> roadmap(List<VersionInfo> versions, List<IssueInfo> issues) {
        Map<String, List<IssueInfo>> byTarget = new HashMap<>();
        for (IssueInfo i : issues)
            if (!norm(i.targetVersion()).isEmpty()) byTarget.computeIfAbsent(key(i.projectId(), i.targetVersion()), k -> new ArrayList<>()).add(i);

        Comparator<VersionInfo> order = Comparator.comparing((VersionInfo v) -> v.projectName() == null ? "" : v.projectName().toLowerCase(Locale.ROOT))
                .thenComparing(VersionInfo::dateOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(v -> norm(v.version()));
        List<Block> out = new ArrayList<>();
        for (VersionInfo v : versions.stream().filter(v -> !v.released() && !v.obsolete()).sorted(order).toList()) {
            List<IssueInfo> list = byTarget.getOrDefault(key(v.projectId(), v.version()), List.of());
            if (list.isEmpty()) continue;
            // open issues first, resolved ones at the end, each group by id
            List<IssueInfo> sorted = list.stream().sorted(Comparator.comparing((IssueInfo i) -> i.status() >= RESOLVED)
                    .thenComparing(IssueInfo::id)).toList();
            out.add(block(v, sorted));
        }
        return out;
    }

    public static List<Block> changelog(List<VersionInfo> versions, List<IssueInfo> issues) {
        Map<String, List<IssueInfo>> byFixed = new HashMap<>();
        for (IssueInfo i : issues)
            if (!norm(i.fixedInVersion()).isEmpty() && i.status() >= RESOLVED)
                byFixed.computeIfAbsent(key(i.projectId(), i.fixedInVersion()), k -> new ArrayList<>()).add(i);

        Comparator<VersionInfo> order = Comparator.comparing((VersionInfo v) -> v.projectName() == null ? "" : v.projectName().toLowerCase(Locale.ROOT))
                .thenComparing(VersionInfo::dateOrder, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()).reversed())
                .thenComparing((VersionInfo v) -> norm(v.version()), Comparator.reverseOrder());
        List<Block> out = new ArrayList<>();
        for (VersionInfo v : versions.stream().filter(v -> !v.obsolete()).sorted(order).toList()) {
            List<IssueInfo> list = byFixed.getOrDefault(key(v.projectId(), v.version()), List.of());
            if (list.isEmpty()) continue;
            out.add(block(v, list.stream().sorted(Comparator.comparing(IssueInfo::id)).toList()));
        }
        return out;
    }

    private static String key(Long projectId, String version) { return projectId + "|" + norm(version); }

    private static Block block(VersionInfo v, List<IssueInfo> issues) {
        int resolved = (int) issues.stream().filter(i -> i.status() >= RESOLVED).count();
        List<Item> items = issues.stream().map(i -> new Item(i.id(), i.summary(), i.status(), i.resolution(), i.priority(),
                i.handler() == null ? "" : i.handler(), i.category() == null ? "" : i.category())).toList();
        return new Block(v.projectId(), v.projectName(), v.versionId(), v.version(), v.description(), v.dateOrder(),
                v.released(), issues.size(), resolved, items);
    }
}
