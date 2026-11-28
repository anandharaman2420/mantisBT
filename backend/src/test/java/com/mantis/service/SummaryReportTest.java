package com.mantis.service;

import com.mantis.service.SummaryReport.Bucket;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

/** Pure unit tests of the date windows and age buckets of the Summary report. */
class SummaryReportTest {
    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    @Test void ageIsMeasuredInWholeDaysAndNeverNegative() {
        assertEquals(0, SummaryReport.ageDays(NOW.minusSeconds(86_399), NOW));
        assertEquals(1, SummaryReport.ageDays(NOW.minus(Duration.ofDays(1)), NOW));
        assertEquals(7, SummaryReport.ageDays(NOW.minus(Duration.ofDays(7)).minusSeconds(5), NOW));
        assertEquals(0, SummaryReport.ageDays(NOW.plusSeconds(3600), NOW), "a submission 'in the future' (clock skew) is age 0");
    }

    @Test void bucketBoundaries() {
        int[][] expected = {{0, 0}, {6, 0}, {7, 1}, {29, 1}, {30, 2}, {89, 2}, {90, 3}, {179, 3}, {180, 4}, {364, 4}, {365, 5}, {5000, 5}};
        for (int[] e : expected) assertEquals(e[1], SummaryReport.bucketIndex(e[0]), "age " + e[0]);
    }

    @Test void everyAgeBelongsToExactlyOneBucketAndTheDatabaseRangeAgreesWithBucketIndex() {
        // walk back in time in 17-minute steps over 800 days: the SQL-style range test must pick the same single bucket
        for (long minutes = 0; minutes < 800L * 24 * 60; minutes += 17) {
            Instant submitted = NOW.minus(Duration.ofMinutes(minutes));
            int found = -1, count = 0;
            for (int i = 0; i < SummaryReport.AGE_BUCKETS.size(); i++)
                if (SummaryReport.matches(SummaryReport.AGE_BUCKETS.get(i), submitted, NOW)) { found = i; count++; }
            assertEquals(1, count, "exactly one bucket for " + minutes + " minutes old");
            assertEquals(SummaryReport.bucketIndex(SummaryReport.ageDays(submitted, NOW)), found, "bucket for " + minutes + " minutes old");
        }
    }

    @Test void exactlySevenDaysOldFallsIntoTheSecondBucket() {
        Instant seven = NOW.minus(Duration.ofDays(7));
        assertTrue(SummaryReport.matches(SummaryReport.AGE_BUCKETS.get(1), seven, NOW));
        assertFalse(SummaryReport.matches(SummaryReport.AGE_BUCKETS.get(0), seven, NOW));
    }

    @Test void bucketsAreContiguous() {
        for (int i = 0; i + 1 < SummaryReport.AGE_BUCKETS.size(); i++)
            assertEquals((int) SummaryReport.AGE_BUCKETS.get(i).maxDays(), SummaryReport.AGE_BUCKETS.get(i + 1).minDays(), "no gap after bucket " + i);
        assertNull(SummaryReport.AGE_BUCKETS.get(SummaryReport.AGE_BUCKETS.size() - 1).maxDays(), "the last bucket is open ended");
        assertEquals(0, SummaryReport.AGE_BUCKETS.get(0).minDays());
    }

    @Test void windowsAreAscendingAndStartAtOneDay() {
        int[] w = SummaryReport.WINDOW_DAYS;
        assertEquals(1, w[0]);
        for (int i = 0; i + 1 < w.length; i++) assertTrue(w[i] < w[i + 1], "ascending at " + i);
    }

    @Test void emptyReportHasEmptyListsNotNulls() {
        SummaryReport.Report r = SummaryReport.Report.empty();
        assertEquals(0, r.total());
        assertTrue(r.byHandler().isEmpty() && r.byDate().isEmpty() && r.mostActive().isEmpty());
    }
}
