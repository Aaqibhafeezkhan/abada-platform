package com.abada.engine.persistence.repository;

import com.abada.engine.persistence.entity.ForEachItemEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ForEachItemRepository extends JpaRepository<ForEachItemEntity, Long> {
    List<ForEachItemEntity> findByParentInstanceIdAndParentTokenIdOrderByItemIndex(String parentInstanceId,
            String parentTokenId);

    Optional<ForEachItemEntity> findByChildInstanceId(String childInstanceId);

    void deleteByParentInstanceIdAndParentTokenId(String parentInstanceId, String parentTokenId);
}
