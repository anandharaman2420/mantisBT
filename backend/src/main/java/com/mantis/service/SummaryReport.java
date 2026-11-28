package com.mantis.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Shapes and the pure rules of the Summary report (port of Mantis summary_page.php), without any framework dependency so the
 * rules can be unit tested. SummaryService fills it with database aggregates of the issues the user may see.
 */
public final class SummaryReport {
    private SummaryReport() {}

    /** "Opened / resolved in the last N days" rows, like the Mantis "by date" table */
    public static final int[] WINDOW_DAYS = {1, 2, 3, 7, 30, 60, 90, 180, 365};

    /** age of an open issue in whole days: age >= minDays and (maxDays == null or age < maxDays) */
    public record Bucket(String label, int minDays, Integer maxDays) {}

    public static final List<Bucket> AGE_BUCKETS = List.of(
            new Bucket("less than 1 week", 0, 7), new Bucket("1 week to 1 month", 7, 30), new Bucket("1 to 3 months", 30, 90),
            new Bucket("3 to 6 months", 90, 180), new Bucket("6 months to 1 year", 180, 365), new Bucket("1 year or older", 365, null));

    /** whole days between submission and now (never negative, so clock skew cannot produce a bucket-less issue) */
    public static long ageDays(Instant submitted, Instant now) {
        return Math.max(0, Duration.between(submitted, now).toDays());
    }

    /** index in AGE_BUCKETS of an issue that old */
    public static int bucketIndex(long ageDays) {
        for (int i = 0; i < AGE_BUCKETS.size(); i++) {
            Bucket b = AGE_BUCKETS.get(i);
            if (ageDays >= b.minDays() && (b.maxDays() == null || ageDays < b.maxDays())) return i;
        }
        throw new IllegalStateException("no bucket for " + ageDays);
    }

    /** the instant a submission date must not be later than for the issue to be at least minDays old */
    public static Instant olderBound(Bucket b, Instant now) { return now.minus(Duration.ofDays(b.minDays())); }

    /** the instant a submission date must be later than for the issue to be younger than maxDays; null = no limit */
    public static Instant youngerBound(Bucket b, Instant now) { return b.maxDays() == null ? null : now.minus(Duration.ofDays(b.maxDays())); }

    /** the same test the database query makes: submitted <= olderBound and submitted > youngerBound (the tests prove they agree) */
    public static boolean matches(Bucket b, Instant submitted, Instant now) {
        Instant young = youngerBound(b, now);
        return !submitted.isAfter(olderBound(b, now)) && (young == null || submitted.isAfter(young));
    }

    // ---------------------------------------------------------------- result shapes (serialised as JSON)
    public record Count(String key, String label, long total, long open) {}
    public record Person(Long id, String name, long open, long resolved, long closed, long total) {}
    public record Day(int days, long opened, long resolved) {}
    public record Age(String label, long count) {}
    public record OldIssue(Long id, String summary, int status, String handler, long ageDays) {}
    public record ActiveIssue(Long id, String summary, int status, long notes) {}

    public record Report(long total, long open, long resolved, long closed,
                         List<Count> byStatus, List<Count> bySeverity, List<Count> byPriority, List<Count> byResolution,
                         List<Count> byCategory, List<Count> byProject, List<Person> byHandler, List<Person> byReporter,
                         List<Day> byDate, List<Age> openAge, List<OldIssue> longestOpen, List<ActiveIssue> mostActive) {
        public static Report empty() {
            return new Report(0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of());
        }
    }
}
