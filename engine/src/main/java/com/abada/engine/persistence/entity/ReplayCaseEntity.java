package com.abada.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** One recorded case replayed on a proposal (E15): identifiers, outcome names and numbers only. */
@Entity
@Table(name = "replay_cases")
public class ReplayCaseEntity {
    public enum State { PENDING, RUNNING, DONE, NEEDS_HUMAN, NEEDS_EXTERNAL, SKIPPED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(name = "run_id") private String runId;
    @Column(name = "original_instance_id") private String originalInstanceId;
    @Enumerated(EnumType.STRING) private State state = State.PENDING;
    private String reason;
    @Column(name = "stopped_at") private String stoppedAt;
    @Column(name = "inputs_redacted") private boolean inputsRedacted;
    @Column(name = "originally_overridden") private Boolean originallyOverridden;
    @Column(name = "original_path_json", columnDefinition = "TEXT") private String originalPathJson;
    @Column(name = "replay_path_json", columnDefinition = "TEXT") private String replayPathJson;
    @Column(name = "diff_json", columnDefinition = "TEXT") private String diffJson;
    private Boolean changed;
    @Column(name = "last_confidence") private Double lastConfidence;
    @Column(name = "tools_skipped") private String toolsSkipped;
    @Column(name = "cost_usd") private BigDecimal costUsd;
    @Column(name = "cost_unpriced") private boolean costUnpriced;
    @Column(name = "lease_owner") private String leaseOwner;
    @Column(name = "lease_until") private Instant leaseUntil;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;

    public long getId() { return id; }
    public String getRunId() { return runId; }
    public void setRunId(String value) { runId = value; }
    public String getOriginalInstanceId() { return originalInstanceId; }
    public void setOriginalInstanceId(String value) { originalInstanceId = value; }
    public State getState() { return state; }
    public void setState(State value) { state = value; }
    public String getReason() { return reason; }
    public void setReason(String value) {
        reason = value == null ? null : value.substring(0, Math.min(value.length(), 1000));
    }
    public String getStoppedAt() { return stoppedAt; }
    public void setStoppedAt(String value) { stoppedAt = value; }
    public boolean isInputsRedacted() { return inputsRedacted; }
    public void setInputsRedacted(boolean value) { inputsRedacted = value; }
    public Boolean getOriginallyOverridden() { return originallyOverridden; }
    public void setOriginallyOverridden(Boolean value) { originallyOverridden = value; }
    public String getOriginalPathJson() { return originalPathJson; }
    public void setOriginalPathJson(String value) { originalPathJson = value; }
    public String getReplayPathJson() { return replayPathJson; }
    public void setReplayPathJson(String value) { replayPathJson = value; }
    public String getDiffJson() { return diffJson; }
    public void setDiffJson(String value) { diffJson = value; }
    public Boolean getChanged() { return changed; }
    public void setChanged(Boolean value) { changed = value; }
    public Double getLastConfidence() { return lastConfidence; }
    public void setLastConfidence(Double value) { lastConfidence = value; }
    public String getToolsSkipped() { return toolsSkipped; }
    public void setToolsSkipped(String value) { toolsSkipped = value; }
    public BigDecimal getCostUsd() { return costUsd; }
    public void setCostUsd(BigDecimal value) { costUsd = value; }
    public boolean isCostUnpriced() { return costUnpriced; }
    public void setCostUnpriced(boolean value) { costUnpriced = value; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String value) { leaseOwner = value; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant value) { leaseUntil = value; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant value) { startedAt = value; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant value) { finishedAt = value; }
}
