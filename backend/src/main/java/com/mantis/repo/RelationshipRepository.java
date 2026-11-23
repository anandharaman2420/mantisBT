package com.mantis.repo;

import com.mantis.domain.IssueRelationship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface RelationshipRepository extends JpaRepository<IssueRelationship, Long> {
    @Query("select r from IssueRelationship r where r.source.id = :id or r.destination.id = :id order by r.id")
    List<IssueRelationship> findAllFor(@Param("id") Long id);

    @Query("select count(r) > 0 from IssueRelationship r where " +
           "(r.source.id = :a and r.destination.id = :b) or (r.source.id = :b and r.destination.id = :a)")
    boolean existsBetween(@Param("a") Long a, @Param("b") Long b);

    @Query("delete from IssueRelationship r where r.source.id = :id or r.destination.id = :id")
    @org.springframework.data.jpa.repository.Modifying
    void deleteAllFor(@Param("id") Long id);
}
