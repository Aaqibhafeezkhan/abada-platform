package com.abada.engine.persistence.repository;

import com.abada.engine.persistence.entity.InsightAgentFactEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InsightAgentFactRepository extends JpaRepository<InsightAgentFactEntity, Long> {

    boolean existsByVisitId(String visitId);

    /** The newest agent visit of the instance still waiting for this reviewer (rework loops pair in order). */
    Optional<InsightAgentFactEntity>
            findFirstByProcessInstanceIdAndAgentNodeAndReviewNodeAndReviewedAtIsNullOrderByEndedAtDesc(
                    String processInstanceId, String agentNode, String reviewNode);

    List<InsightAgentFactEntity> findByEndedAtGreaterThanEqualAndEndedAtLessThan(Instant from, Instant to);

    /** Reviews are analysed in the window their decision fell in, not the window of the agent visit. */
    List<InsightAgentFactEntity> findByReviewedAtGreaterThanEqualAndReviewedAtLessThan(Instant from, Instant to);

    List<InsightAgentFactEntity>
            findByProjectIdAndDefinitionKeyAndDefinitionDeploymentIdAndAgentNodeAndEndedAtBefore(
                    String projectId, String definitionKey, String definitionDeploymentId,
                    String agentNode, Instant before);
}
