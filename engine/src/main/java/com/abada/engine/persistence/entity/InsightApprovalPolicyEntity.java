package com.abada.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "insight_approval_policies")
@IdClass(InsightApprovalPolicyId.class)
public class InsightApprovalPolicyEntity {
    @Id
    @Column(name = "project_id")
    private String projectId;

    @Id
    @Column(name = "definition_key")
    private String definitionKey;

    @Version
    @Column(name = "policy_version")
    private long policyVersion;

    @Column(name = "required_approvals")
    private int requiredApprovals = 1;

    @Column(name = "required_groups")
    private String requiredGroups = "abada-insight-reviewer";

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_mode")
    private InsightProposalEntity.ApprovalMode approvalMode = InsightProposalEntity.ApprovalMode.PARALLEL;

    /** When set, an approval is refused until a finished replay of the proposal's current source exists (E15). */
    @Column(name = "replay_required")
    private boolean replayRequired;

    @Column(name = "replay_min_cases")
    private int replayMinCases = 10;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    public String getProjectId() { return projectId; }
    public void setProjectId(String value) { projectId = value; }
    public String getDefinitionKey() { return definitionKey; }
    public void setDefinitionKey(String value) { definitionKey = value; }
    public long getPolicyVersion() { return policyVersion; }
    public int getRequiredApprovals() { return requiredApprovals; }
    public void setRequiredApprovals(int value) { requiredApprovals = value; }
    public String getRequiredGroups() { return requiredGroups; }
    public void setRequiredGroups(String value) { requiredGroups = value; }
    public InsightProposalEntity.ApprovalMode getApprovalMode() { return approvalMode; }
    public void setApprovalMode(InsightProposalEntity.ApprovalMode value) { approvalMode = value; }
    public boolean isReplayRequired() { return replayRequired; }
    public void setReplayRequired(boolean value) { replayRequired = value; }
    public int getReplayMinCases() { return replayMinCases; }
    public void setReplayMinCases(int value) { replayMinCases = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String value) { updatedBy = value; }
}
