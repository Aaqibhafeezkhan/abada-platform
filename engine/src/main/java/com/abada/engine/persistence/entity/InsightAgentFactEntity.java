package com.abada.engine.persistence.entity;

import com.abada.engine.project.ProjectConstants;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One successful agent visit and, when the node declares {@code reviewed_by},
 * the human decision on it (E14). Identifiers, numbers and outcome names only.
 */
@Entity
@Table(name = "insight_agent_facts")
public class InsightAgentFactEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(name = "project_id")
    private String projectId = ProjectConstants.DEFAULT_PROJECT_ID;

    @Column(name = "visit_id")
    private String visitId;

    @Column(name = "definition_key")
    private String definitionKey;

    @Column(name = "definition_deployment_id")
    private String definitionDeploymentId;

    @Column(name = "process_instance_id")
    private String processInstanceId;

    @Column(name = "agent_node")
    private String agentNode;

    private String model;

    private Double confidence;

    @Column(name = "cost_usd")
    private BigDecimal costUsd;

    @Column(name = "cost_unpriced")
    private boolean costUnpriced;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "review_node")
    private String reviewNode;

    @Column(name = "review_outcome")
    private String reviewOutcome;

    private Boolean overridden;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    public long getId() { return id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getVisitId() { return visitId; }
    public void setVisitId(String visitId) { this.visitId = visitId; }
    public String getDefinitionKey() { return definitionKey; }
    public void setDefinitionKey(String definitionKey) { this.definitionKey = definitionKey; }
    public String getDefinitionDeploymentId() { return definitionDeploymentId; }
    public void setDefinitionDeploymentId(String value) { this.definitionDeploymentId = value; }
    public String getProcessInstanceId() { return processInstanceId; }
    public void setProcessInstanceId(String processInstanceId) { this.processInstanceId = processInstanceId; }
    public String getAgentNode() { return agentNode; }
    public void setAgentNode(String agentNode) { this.agentNode = agentNode; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public BigDecimal getCostUsd() { return costUsd; }
    public void setCostUsd(BigDecimal costUsd) { this.costUsd = costUsd; }
    public boolean isCostUnpriced() { return costUnpriced; }
    public void setCostUnpriced(boolean costUnpriced) { this.costUnpriced = costUnpriced; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public String getReviewNode() { return reviewNode; }
    public void setReviewNode(String reviewNode) { this.reviewNode = reviewNode; }
    public String getReviewOutcome() { return reviewOutcome; }
    public void setReviewOutcome(String reviewOutcome) { this.reviewOutcome = reviewOutcome; }
    public Boolean getOverridden() { return overridden; }
    public void setOverridden(Boolean overridden) { this.overridden = overridden; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
}
