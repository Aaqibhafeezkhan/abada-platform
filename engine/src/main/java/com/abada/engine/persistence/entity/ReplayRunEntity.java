package com.abada.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A replay of one Insight proposal on recorded cases (E15). */
@Entity
@Table(name = "replay_runs")
public class ReplayRunEntity {
    public enum Status { PLANNED, RUNNING, COMPLETED, PARTIAL, FAILED, CANCELLED }

    @Id
    private String id = UUID.randomUUID().toString();
    @Column(name = "project_id") private String projectId;
    @Column(name = "proposal_id") private long proposalId;
    @Column(name = "proposal_checksum") private String proposalChecksum;
    @Column(name = "definition_key") private String definitionKey;
    @Column(name = "base_deployment_id") private String baseDeploymentId;
    @Enumerated(EnumType.STRING) private Status status = Status.PLANNED;
    @Column(name = "requested_cases") private int requestedCases;
    @Column(name = "without_inputs") private int withoutInputs;
    @Column(name = "budget_usd") private BigDecimal budgetUsd;
    @Column(name = "estimated_cost_usd") private BigDecimal estimatedCostUsd;
    @Column(name = "estimate_unpriced") private boolean estimateUnpriced;
    @Column(name = "cost_usd") private BigDecimal costUsd = BigDecimal.ZERO;
    @Column(name = "cost_unpriced") private boolean costUnpriced;
    @Column(name = "requested_by") private String requestedBy;
    @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
    @Column(name = "purge_after") private Instant purgeAfter;

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String value) { projectId = value; }
    public long getProposalId() { return proposalId; }
    public void setProposalId(long value) { proposalId = value; }
    public String getProposalChecksum() { return proposalChecksum; }
    public void setProposalChecksum(String value) { proposalChecksum = value; }
    public String getDefinitionKey() { return definitionKey; }
    public void setDefinitionKey(String value) { definitionKey = value; }
    public String getBaseDeploymentId() { return baseDeploymentId; }
    public void setBaseDeploymentId(String value) { baseDeploymentId = value; }
    public Status getStatus() { return status; }
    public void setStatus(Status value) { status = value; }
    public int getRequestedCases() { return requestedCases; }
    public void setRequestedCases(int value) { requestedCases = value; }
    public int getWithoutInputs() { return withoutInputs; }
    public void setWithoutInputs(int value) { withoutInputs = value; }
    public BigDecimal getBudgetUsd() { return budgetUsd; }
    public void setBudgetUsd(BigDecimal value) { budgetUsd = value; }
    public BigDecimal getEstimatedCostUsd() { return estimatedCostUsd; }
    public void setEstimatedCostUsd(BigDecimal value) { estimatedCostUsd = value; }
    public boolean isEstimateUnpriced() { return estimateUnpriced; }
    public void setEstimateUnpriced(boolean value) { estimateUnpriced = value; }
    public BigDecimal getCostUsd() { return costUsd; }
    public void setCostUsd(BigDecimal value) { costUsd = value; }
    public boolean isCostUnpriced() { return costUnpriced; }
    public void setCostUnpriced(boolean value) { costUnpriced = value; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String value) { requestedBy = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant value) { startedAt = value; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant value) { finishedAt = value; }
    public Instant getPurgeAfter() { return purgeAfter; }
    public void setPurgeAfter(Instant value) { purgeAfter = value; }
}
