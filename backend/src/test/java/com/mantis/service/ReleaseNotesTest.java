package com.mantis.service;

import com.mantis.service.ReleaseNotes.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Pure unit tests of the roadmap / changelog rules (no Spring, no database). */
class ReleaseNotesTest {
    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant MAR = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant JUN = Instant.parse("2026-06-01T00:00:00Z");

    private static VersionInfo v(long project, String projectName, long id, String name, Instant date, boolean released, boolean obsolete) {
        return new VersionInfo(project, projectName, id, name, "", date, released, obsolete);
    }
    private static IssueInfo issue(long id, long project, int status, String target, String fixedIn) {
        return new IssueInfo(id, project, "issue " + id, status, 10, 30, "dev", "UI", target, fixedIn);
    }

    @Test void roadmapListsOnlyUnreleasedVersionsWithIssuesAndCountsProgress() {
        List<VersionInfo> versions = List.of(v(1, "Alpha", 1, "1.0", JAN, true, false), v(1, "Alpha", 2, "2.0", MAR, false, false),
                v(1, "Alpha", 3, "3.0", JUN, false, false), v(1, "Alpha", 4, "0.9", JAN, false, true));
        List<IssueInfo> issues = List.of(issue(1, 1, 10, "2.0", ""), issue(2, 1, 80, "2.0", ""), issue(3, 1, 90, "2.0", ""),
                issue(4, 1, 50, "1.0", ""), issue(5, 1, 10, "0.9", ""));
        List<Block> r = ReleaseNotes.roadmap(versions, issues);
        assertEquals(1, r.size(), "1.0 is released, 0.9 is obsolete, 3.0 has no issues");
        Block b = r.get(0);
        assertEquals("2.0", b.version());
        assertEquals(3, b.total());
        assertEquals(2, b.resolved());
        assertEquals(List.of(1L, 2L, 3L), b.issues().stream().map(Item::id).toList());
    }

    @Test void roadmapPutsOpenIssuesBeforeResolvedOnes() {
        List<IssueInfo> issues = List.of(issue(1, 1, 90, "2.0", ""), issue(2, 1, 10, "2.0", ""), issue(3, 1, 80, "2.0", ""), issue(4, 1, 50, "2.0", ""));
        Block b = ReleaseNotes.roadmap(List.of(v(1, "Alpha", 2, "2.0", MAR, false, false)), issues).get(0);
        assertEquals(List.of(2L, 4L, 1L, 3L), b.issues().stream().map(Item::id).toList());
    }

    @Test void roadmapOrdersBySoonestVersionAndPutsUndatedLast() {
        List<VersionInfo> versions = List.of(v(1, "Alpha", 1, "3.0", null, false, false), v(1, "Alpha", 2, "2.0", MAR, false, false),
                v(1, "Alpha", 3, "1.5", JAN, false, false));
        List<IssueInfo> issues = List.of(issue(1, 1, 10, "3.0", ""), issue(2, 1, 10, "2.0", ""), issue(3, 1, 10, "1.5", ""));
        assertEquals(List.of("1.5", "2.0", "3.0"), ReleaseNotes.roadmap(versions, issues).stream().map(Block::version).toList());
    }

    @Test void versionNamesMatchIgnoringCaseAndBlanksButNeverAcrossProjects() {
        List<VersionInfo> versions = List.of(v(1, "Alpha", 1, "2.0-RC", MAR, false, false), v(2, "Beta", 2, "2.0-RC", MAR, false, false));
        List<IssueInfo> issues = List.of(issue(1, 1, 10, "  2.0-rc ", ""), issue(2, 2, 10, "2.0-RC", ""), issue(3, 2, 10, "2.0-rc", ""));
        List<Block> r = ReleaseNotes.roadmap(versions, issues);
        assertEquals(2, r.size());
        assertEquals("Alpha", r.get(0).projectName());
        assertEquals(1, r.get(0).total());
        assertEquals(2, r.get(1).total());
    }

    @Test void issuesWithAnUnknownOrEmptyVersionAreIgnored() {
        List<IssueInfo> issues = List.of(issue(1, 1, 10, "9.9", ""), issue(2, 1, 10, "", ""), issue(3, 1, 10, null, null));
        assertTrue(ReleaseNotes.roadmap(List.of(v(1, "Alpha", 1, "2.0", MAR, false, false)), issues).isEmpty());
    }

    @Test void changelogListsResolvedIssuesOfReleasedAndUnreleasedVersionsNewestFirst() {
        List<VersionInfo> versions = List.of(v(1, "Alpha", 1, "1.0", JAN, true, false), v(1, "Alpha", 2, "2.0", MAR, true, false),
                v(1, "Alpha", 3, "3.0", JUN, false, false), v(1, "Alpha", 4, "0.9", JAN, true, true));
        List<IssueInfo> issues = List.of(issue(1, 1, 80, "", "1.0"), issue(2, 1, 90, "", "2.0"), issue(3, 1, 80, "", "2.0"),
                issue(4, 1, 50, "", "2.0"), issue(5, 1, 80, "", "3.0"), issue(6, 1, 80, "", "0.9"));
        List<Block> r = ReleaseNotes.changelog(versions, issues);
        assertEquals(List.of("3.0", "2.0", "1.0"), r.stream().map(Block::version).toList(), "newest first, obsolete left out");
        assertEquals(List.of(2L, 3L), r.get(1).issues().stream().map(Item::id).toList(), "issue 4 is not resolved");
        assertEquals(2, r.get(1).total());
        assertEquals(2, r.get(1).resolved());
    }

    @Test void changelogIgnoresIssuesWithoutFixedInVersion() {
        List<IssueInfo> issues = List.of(issue(1, 1, 80, "2.0", ""), issue(2, 1, 90, "2.0", null));
        assertTrue(ReleaseNotes.changelog(List.of(v(1, "Alpha", 1, "2.0", MAR, true, false)), issues).isEmpty());
    }

    @Test void itemsCarryHandlerAndCategoryAndNeverNull() {
        IssueInfo i = new IssueInfo(7L, 1L, "boom", 10, 10, 40, null, null, "2.0", "");
        Item it = ReleaseNotes.roadmap(List.of(v(1, "Alpha", 1, "2.0", MAR, false, false)), List.of(i)).get(0).issues().get(0);
        assertEquals("", it.handler());
        assertEquals("", it.category());
        assertEquals(40, it.priority());
    }
}
