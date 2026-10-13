package com.abada.engine.persistence.repository;

import com.abada.engine.persistence.entity.ReplayCaseEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReplayCaseRepository extends JpaRepository<ReplayCaseEntity, Long> {
    List<ReplayCaseEntity> findByRunIdOrderById(String runId);

    long countByRunIdAndStateIn(String runId, Collection<ReplayCaseEntity.State> states);

    /**
     * Cases of running runs that nobody holds: pending, or running under a lease that expired (a crashed
     * replica). Replicas claim disjoint rows.
     */
    @Query(value = "select c.* from replay_cases c join replay_runs r on r.id = c.run_id "
            + "where r.status = 'RUNNING' and c.state in ('PENDING', 'RUNNING') "
            + "and (c.lease_until is null or c.lease_until < :now) order by c.id limit :limit "
            + "for update of c skip locked", nativeQuery = true)
    List<ReplayCaseEntity> claimable(@Param("now") Instant now, @Param("limit") int limit);
}
