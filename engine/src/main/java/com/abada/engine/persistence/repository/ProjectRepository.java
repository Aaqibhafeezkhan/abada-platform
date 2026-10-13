package com.abada.engine.persistence.repository;

import com.abada.engine.persistence.entity.ProjectEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<ProjectEntity, String> {
    Optional<ProjectEntity> findBySlug(String slug);
    Page<ProjectEntity> findByStatus(ProjectEntity.Status status, Pageable pageable);
    List<ProjectEntity> findAllByStatus(ProjectEntity.Status status);

    /** Serializes commands that must see each other's committed result, such as rollbacks (E16). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProjectEntity p where p.id = :id")
    Optional<ProjectEntity> findByIdForUpdate(@Param("id") String id);
}
