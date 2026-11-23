package com.mantis.repo;

import com.mantis.domain.Note;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface NoteRepository extends JpaRepository<Note, Long> {
    List<Note> findByIssueIdOrderByDateSubmittedAsc(Long issueId);
    void deleteByIssueId(Long issueId);

    @Query("select coalesce(sum(n.timeTracking), 0) from Note n where n.issue.id = :id")
    long sumMinutes(@Param("id") Long issueId);

    /** all time entries in a date range for the given projects (billing / time report) */
    @Query("select n from Note n join fetch n.issue i join fetch i.project join fetch n.reporter " +
           "where n.timeTracking > 0 and n.dateSubmitted >= :from and n.dateSubmitted < :to and i.project.id in :pids " +
           "order by n.dateSubmitted")
    List<Note> timeEntries(@Param("from") Instant from, @Param("to") Instant to, @Param("pids") Collection<Long> projectIds);
}
