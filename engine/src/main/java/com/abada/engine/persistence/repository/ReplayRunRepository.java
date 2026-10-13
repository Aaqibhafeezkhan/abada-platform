package com.abada.engine.persistence.repository;

import com.abada.engine.persistence.entity.ReplayRunEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReplayRunRepository extends JpaRepository<ReplayRunEntity, String> {
    List<ReplayRunEntity> findByProjectIdAndProposalIdOrderByCreatedAtDesc(String projectId, long proposalId);

    Optional<ReplayRunEntity> findByIdAndProjectId(String id, String projectId);

    boolean existsByStatus(ReplayRunEntity.Status status);

    List<ReplayRunEntity> findByStatus(ReplayRunEntity.Status status);

    boolean existsByProposalIdAndStatusIn(long proposalId, Collection<ReplayRunEntity.Status> statuses);

    /** A finished run that qualifies a proposal at its current checksum, newest first. */
    List<ReplayRunEntity> findByProposalIdAndProposalChecksumAndStatusInOrderByCreatedAtDesc(
            long proposalId, String checksum, Collection<ReplayRunEntity.Status> statuses);

    @Modifying
    @Query("delete from ReplayRunEntity r where r.purgeAfter <= :now and r.status not in ('PLANNED', 'RUNNING')")
    int deleteDue(@Param("now") Instant now);
}
