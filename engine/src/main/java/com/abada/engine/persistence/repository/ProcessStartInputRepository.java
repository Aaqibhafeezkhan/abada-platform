package com.abada.engine.persistence.repository;

import com.abada.engine.persistence.entity.ProcessStartInputEntity;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessStartInputRepository extends JpaRepository<ProcessStartInputEntity, String> {

    /** Clears every payload past its retention; idempotent, so replicas may sweep at once. */
    @Modifying
    @Query("update ProcessStartInputEntity i set i.inputsEnc = null, i.purgedAt = :now "
            + "where i.purgeAfter <= :now and i.purgedAt is null and i.inputsEnc is not null")
    int purgeDue(@Param("now") Instant now);
}
